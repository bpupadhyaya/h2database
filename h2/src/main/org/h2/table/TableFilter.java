/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.table;

import java.util.ArrayList;

import org.h2.command.Parser;
import org.h2.command.dml.Select;
import org.h2.engine.Right;
import org.h2.engine.Session;
import org.h2.engine.SysProperties;
import org.h2.engine.UndoLogRecord;
import org.h2.expression.Comparison;
import org.h2.expression.ConditionAndOr;
import org.h2.expression.Expression;
import org.h2.expression.ExpressionColumn;
import org.h2.index.Index;
import org.h2.index.IndexCondition;
import org.h2.index.IndexCursor;
import org.h2.index.IndexLookupBatch;
import org.h2.index.ViewIndex;
import org.h2.message.DbException;
import org.h2.result.Row;
import org.h2.result.SearchRow;
import org.h2.result.SortOrder;
import org.h2.util.New;
import org.h2.util.StatementBuilder;
import org.h2.util.StringUtils;
import org.h2.value.Value;
import org.h2.value.ValueLong;
import org.h2.value.ValueNull;

/**
 * A table filter represents a table that is used in a query. There is one such
 * object whenever a table (or view) is used in a query. For example the
 * following query has 2 table filters: SELECT * FROM TEST T1, TEST T2.
 */
public class TableFilter implements ColumnResolver {

    private static final int BEFORE_FIRST = 0, FOUND = 1, AFTER_LAST = 2,
            NULL_ROW = 3;

    /**
     * Whether this is a direct or indirect (nested) outer join
     */
    protected boolean joinOuterIndirect;

    private Session session;

    private final Table table;
    private final Select select;
    private String alias;
    private Index index;
    private int[] masks;
    private int scanCount;
    private boolean evaluatable;

    /**
     * Batched join support.
     */
    private JoinBatch joinBatch;
    private int joinFilterId = -1;

    /**
     * Indicates that this filter is used in the plan.
     */
    private boolean used;

    /**
     * The filter used to walk through the index.
     */
    private final IndexCursor cursor;

    /**
     * The index conditions used for direct index lookup (start or end).
     */
    private final ArrayList<IndexCondition> indexConditions = New.arrayList();

    /**
     * Additional conditions that can't be used for index lookup, but for row
     * filter for this table (ID=ID, NAME LIKE '%X%')
     */
    private Expression filterCondition;

    /**
     * The complete join condition.
     */
    private Expression joinCondition;

    private SearchRow currentSearchRow;
    private Row current;
    private int state;

    /**
     * The joined table (if there is one).
     */
    private TableFilter join;

    /**
     * Whether this is an outer join.
     */
    private boolean joinOuter;

    /**
     * The nested joined table (if there is one).
     */
    private TableFilter nestedJoin;

    private ArrayList<Column> naturalJoinColumns;
    private boolean foundOne;
    private Expression fullCondition;
    private final int hashCode;
    private final int orderInFrom;

    /**
     * Create a new table filter object.
     *
     * @param session the session
     * @param table the table from where to read data
     * @param alias the alias name
     * @param rightsChecked true if rights are already checked
     * @param select the select statement
     * @param orderInFrom Original order number of this table filter in FROM clause.
     */
    public TableFilter(Session session, Table table, String alias,
            boolean rightsChecked, Select select, int orderInFrom) {
        this.session = session;
        this.table = table;
        this.alias = alias;
        this.select = select;
        this.cursor = new IndexCursor(this);
        if (!rightsChecked) {
            session.getUser().checkRight(table, Right.SELECT);
        }
        hashCode = session.nextObjectId();
        this.orderInFrom = orderInFrom;
    }

    public int getOrderInFrom() {
        return orderInFrom;
    }

    public IndexCursor getIndexCursor() {
        return cursor;
    }

    @Override
    public Select getSelect() {
        return select;
    }

    public Table getTable() {
        return table;
    }

    /**
     * Lock the table. This will also lock joined tables.
     *
     * @param s the session
     * @param exclusive true if an exclusive lock is required
     * @param forceLockEvenInMvcc lock even in the MVCC mode
     */
    public void lock(Session s, boolean exclusive, boolean forceLockEvenInMvcc) {
        table.lock(s, exclusive, forceLockEvenInMvcc);
        if (join != null) {
            join.lock(s, exclusive, forceLockEvenInMvcc);
        }
    }

    /**
     * Get the best plan item (index, cost) to use use for the current join
     * order.
     *
     * @param s the session
     * @param filters all joined table filters
     * @param filter the current table filter index
     * @return the best plan item
     */
    public PlanItem getBestPlanItem(Session s, TableFilter[] filters, int filter) {
        PlanItem item1 = null;
        SortOrder sortOrder = null;
        if (select != null) {
            sortOrder = select.getSortOrder();
        }
        if (indexConditions.size() == 0) {
            item1 = new PlanItem();
            item1.setIndex(table.getScanIndex(s, null, filters, filter, sortOrder));
            item1.cost = item1.getIndex().getCost(s, null, filters, filter, sortOrder);
        }
        int len = table.getColumns().length;
        int[] masks = new int[len];
        for (IndexCondition condition : indexConditions) {
            if (condition.isEvaluatable()) {
                if (condition.isAlwaysFalse()) {
                    masks = null;
                    break;
                }
                int id = condition.getColumn().getColumnId();
                if (id >= 0) {
                    masks[id] |= condition.getMask(indexConditions);
                }
            }
        }
        PlanItem item = table.getBestPlanItem(s, masks, filters, filter, sortOrder);
        item.setMasks(masks);
        // The more index conditions, the earlier the table.
        // This is to ensure joins without indexes run quickly:
        // x (x.a=10); y (x.b=y.b) - see issue 113
        item.cost -= item.cost * indexConditions.size() / 100 / (filter + 1);

        if (item1 != null && item1.cost < item.cost) {
            item = item1;
        }

        if (nestedJoin != null) {
            setEvaluatable(nestedJoin);
            item.setNestedJoinPlan(nestedJoin.getBestPlanItem(s, filters, filter));
            // TODO optimizer: calculate cost of a join: should use separate
            // expected row number and lookup cost
            item.cost += item.cost * item.getNestedJoinPlan().cost;
        }
        if (join != null) {
            setEvaluatable(join);
            do {
                filter++;
            } while (filters[filter] != join);
            item.setJoinPlan(join.getBestPlanItem(s, filters, filter));
            // TODO optimizer: calculate cost of a join: should use separate
            // expected row number and lookup cost
            item.cost += item.cost * item.getJoinPlan().cost;
        }
        return item;
    }

    private void setEvaluatable(TableFilter join) {
        if (session.getDatabase().getSettings().nestedJoins) {
            setEvaluatable(true);
            return;
        }
        // this table filter is now evaluatable - in all sub-joins
        do {
            Expression e = join.getJoinCondition();
            if (e != null) {
                e.setEvaluatable(this, true);
            }
            TableFilter n = join.getNestedJoin();
            if (n != null) {
                setEvaluatable(n);
            }
            join = join.getJoin();
        } while (join != null);
    }

    /**
     * Set what plan item (index, cost, masks) to use.
     *
     * @param item the plan item
     */
    public void setPlanItem(PlanItem item) {
        if (item == null) {
            // invalid plan, most likely because a column wasn't found
            // this will result in an exception later on
            return;
        }
        setIndex(item.getIndex());
        masks = item.getMasks();
        if (nestedJoin != null) {
            if (item.getNestedJoinPlan() != null) {
                nestedJoin.setPlanItem(item.getNestedJoinPlan());
            }
        }
        if (join != null) {
            if (item.getJoinPlan() != null) {
                join.setPlanItem(item.getJoinPlan());
            }
        }
    }

    /**
     * Prepare reading rows. This method will remove all index conditions that
     * can not be used, and optimize the conditions.
     */
    public void prepare() {
        // forget all unused index conditions
        // the indexConditions list may be modified here
        for (int i = 0; i < indexConditions.size(); i++) {
            IndexCondition condition = indexConditions.get(i);
            if (!condition.isAlwaysFalse()) {
                Column col = condition.getColumn();
                if (col.getColumnId() >= 0) {
                    if (index.getColumnIndex(col) < 0) {
                        indexConditions.remove(i);
                        i--;
                    }
                }
            }
        }
        if (nestedJoin != null) {
            if (SysProperties.CHECK && nestedJoin == this) {
                DbException.throwInternalError("self join");
            }
            nestedJoin.prepare();
        }
        if (join != null) {
            if (SysProperties.CHECK && join == this) {
                DbException.throwInternalError("self join");
            }
            join.prepare();
        }
        if (filterCondition != null) {
            filterCondition = filterCondition.optimize(session);
        }
        if (joinCondition != null) {
            joinCondition = joinCondition.optimize(session);
        }
    }

    /**
     * Start the query. This will reset the scan counts.
     *
     * @param s the session
     */
    public void startQuery(Session s) {
        this.session = s;
        scanCount = 0;
        if (nestedJoin != null) {
            nestedJoin.startQuery(s);
        }
        if (join != null) {
            join.startQuery(s);
        }
    }

    /**
     * Reset to the current position.
     */
    public void reset() {
        if (joinBatch != null && joinFilterId == 0) {
            // reset join batch only on top table filter
            joinBatch.reset(true);
            return;
        }
        if (nestedJoin != null) {
            nestedJoin.reset();
        }
        if (join != null) {
            join.reset();
        }
        state = BEFORE_FIRST;
        foundOne = false;
    }

    private boolean isAlwaysTopTableFilter(int filter) {
        if (filter != 0) {
            return false;
        }
        // check if we are at the top table filters all the way up
        SubQueryInfo info = session.getSubQueryInfo();
        for (;;) {
            if (info == null) {
                return true;
            }
            if (info.getFilter() != 0) {
                return false;
            }
            info = info.getUpper();
        }
    }

    /**
     * Attempt to initialize batched join.
     *
     * @param jb join batch if it is already created
     * @return join batch if query runs over index which supports batched lookups, {@code null} otherwise
     */
    public JoinBatch prepareJoinBatch(JoinBatch jb, TableFilter[] filters, int filter) {
        joinBatch = null;
        joinFilterId = -1;
        if (getTable().isView()) {
            session.pushSubQueryInfo(masks, filters, filter, select.getSortOrder());
            try {
                ((ViewIndex) index).getQuery().prepareJoinBatch();
            } finally {
                session.popSubQueryInfo();
            }
        }
        // For globally top table filter we don't need to create lookup batch, because
        // currently it will not be used (this will be shown in ViewIndex.getPlanSQL()). Probably
        // later on it will make sense to create it to better support X IN (...) conditions,
        // but this needs to be implemented separately. If isAlwaysTopTableFilter is false
        // then we either not a top table filter or top table filter in a sub-query, which
        // in turn is not top in outer query, thus we need to enable batching here to allow
        // outer query run batched join against this sub-query.
        IndexLookupBatch lookupBatch = null;
        if (jb == null && select != null && !isAlwaysTopTableFilter(filter)) {
            lookupBatch = index.createLookupBatch(this);
            if (lookupBatch != null) {
                jb = new JoinBatch(filter + 1, join);
            }
        }
        if (jb != null) {
            if (nestedJoin != null) {
                throw DbException.throwInternalError();
            }
            joinBatch = jb;
            joinFilterId = filter;
            if (lookupBatch == null && !isAlwaysTopTableFilter(filter)) {
                // createLookupBatch will be called at most once because jb can be
                // created only if lookupBatch is already not null from the call above.
                lookupBatch = index.createLookupBatch(this);
                if (lookupBatch == null) {
                    // the index does not support lookup batching, need to fake it because we are not top
                    lookupBatch = JoinBatch.createFakeIndexLookupBatch(this);
                }
            }
            jb.register(this, lookupBatch);
        }
        return jb;
    }

    public int getJoinFilterId() {
        return joinFilterId;
    }

    public JoinBatch getJoinBatch() {
        return joinBatch;
    }

    /**
     * Check if there are more rows to read.
     *
     * @return true if there are
     */
    public boolean next() {
        if (joinBatch != null) {
            // will happen only on topTableFilter since joinBatch.next() does not call join.next()
            return joinBatch.next();
        }
        if (state == AFTER_LAST) {
            return false;
        } else if (state == BEFORE_FIRST) {
            cursor.find(session, indexConditions);
            if (!cursor.isAlwaysFalse()) {
                if (nestedJoin != null) {
                    nestedJoin.reset();
                }
                if (join != null) {
                    join.reset();
                }
            }
        } else {
            // state == FOUND || NULL_ROW
            // the last row was ok - try next row of the join
            if (join != null && join.next()) {
                return true;
            }
        }
        while (true) {
            // go to the next row
            if (state == NULL_ROW) {
                break;
            }
            if (cursor.isAlwaysFalse()) {
                state = AFTER_LAST;
            } else if (nestedJoin != null) {
                if (state == BEFORE_FIRST) {
                    state = FOUND;
                }
            } else {
                if ((++scanCount & 4095) == 0) {
                    checkTimeout();
                }
                if (cursor.next()) {
                    currentSearchRow = cursor.getSearchRow();
                    current = null;
                    state = FOUND;
                } else {
                    state = AFTER_LAST;
                }
            }
            if (nestedJoin != null && state == FOUND) {
                if (!nestedJoin.next()) {
                    state = AFTER_LAST;
                    if (joinOuter && !foundOne) {
                        // possibly null row
                    } else {
                        continue;
                    }
                }
            }
            // if no more rows found, try the null row (for outer joins only)
            if (state == AFTER_LAST) {
                if (joinOuter && !foundOne) {
                    setNullRow();
                } else {
                    break;
                }
            }
            if (!isOk(filterCondition)) {
                continue;
            }
            boolean joinConditionOk = isOk(joinCondition);
            if (state == FOUND) {
                if (joinConditionOk) {
                    foundOne = true;
                } else {
                    continue;
                }
            }
            if (join != null) {
                join.reset();
                if (!join.next()) {
                    continue;
                }
            }
            // check if it's ok
            if (state == NULL_ROW || joinConditionOk) {
                return true;
            }
        }
        state = AFTER_LAST;
        return false;
    }

    /**
     * Set the state of this and all nested tables to the NULL row.
     */
    protected void setNullRow() {
        state = NULL_ROW;
        current = table.getNullRow();
        currentSearchRow = current;
        if (nestedJoin != null) {
            nestedJoin.visit(new TableFilterVisitor() {
                @Override
                public void accept(TableFilter f) {
                    f.setNullRow();
                }
            });
        }
    }

    private void checkTimeout() {
        session.checkCanceled();
        // System.out.println(this.alias+ " " + table.getName() + ": " +
        // scanCount);
    }

    boolean isOk(Expression condition) {
        if (condition == null) {
            return true;
        }
        return Boolean.TRUE.equals(condition.getBooleanValue(session));
    }

    /**
     * Get the current row.
     *
     * @return the current row, or null
     */
    public Row get() {
        if (current == null && currentSearchRow != null) {
            current = cursor.get();
        }
        return current;
    }

    /**
     * Set the current row.
     *
     * @param current the current row
     */
    public void set(Row current) {
        // this is currently only used so that check constraints work - to set
        // the current (new) row
        this.current = current;
        this.currentSearchRow = current;
    }

    /**
     * Get the table alias name. If no alias is specified, the table name is
     * returned.
     *
     * @return the alias name
     */
    @Override
    public String getTableAlias() {
        if (alias != null) {
            return alias;
        }
        return table.getName();
    }

    /**
     * Add an index condition.
     *
     * @param condition the index condition
     */
    public void addIndexCondition(IndexCondition condition) {
        indexConditions.add(condition);
    }

    /**
     * Add a filter condition.
     *
     * @param condition the condition
     * @param isJoin if this is in fact a join condition
     */
    public void addFilterCondition(Expression condition, boolean isJoin) {
        if (isJoin) {
            if (joinCondition == null) {
                joinCondition = condition;
            } else {
                joinCondition = new ConditionAndOr(ConditionAndOr.AND,
                        joinCondition, condition);
            }
        } else {
            if (filterCondition == null) {
                filterCondition = condition;
            } else {
                filterCondition = new ConditionAndOr(ConditionAndOr.AND,
                        filterCondition, condition);
            }
        }
    }

    /**
     * Add a joined table.
     *
     * @param filter the joined table filter
     * @param outer if this is an outer join
     * @param nested if this is a nested join
     * @param on the join condition
     */
    public void addJoin(TableFilter filter, boolean outer, boolean nested,
            final Expression on) {
        if (on != null) {
            on.mapColumns(this, 0);
            if (session.getDatabase().getSettings().nestedJoins) {
                visit(new TableFilterVisitor() {
                    @Override
                    public void accept(TableFilter f) {
                        on.mapColumns(f, 0);
                    }
                });
                filter.visit(new TableFilterVisitor() {
                    @Override
                    public void accept(TableFilter f) {
                        on.mapColumns(f, 0);
                    }
                });
            }
        }
        if (nested && session.getDatabase().getSettings().nestedJoins) {
            if (nestedJoin != null) {
                throw DbException.throwInternalError();
            }
            nestedJoin = filter;
            filter.joinOuter = outer;
            if (outer) {
                visit(new TableFilterVisitor() {
                    @Override
                    public void accept(TableFilter f) {
                        f.joinOuterIndirect = true;
                    }
                });
            }
            if (on != null) {
                filter.mapAndAddFilter(on);
            }
        } else {
            if (join == null) {
                join = filter;
                filter.joinOuter = outer;
                if (session.getDatabase().getSettings().nestedJoins) {
                    if (outer) {
                        filter.visit(new TableFilterVisitor() {
                            @Override
                            public void accept(TableFilter f) {
                                f.joinOuterIndirect = true;
                            }
                        });
                    }
                } else {
                    if (outer) {
                        // convert all inner joins on the right hand side to
                        // outer joins
                        TableFilter f = filter.join;
                        while (f != null) {
                            f.joinOuter = true;
                            f = f.join;
                        }
                    }
                }
                if (on != null) {
                    filter.mapAndAddFilter(on);
                }
            } else {
                join.addJoin(filter, outer, nested, on);
            }
        }
    }

    /**
     * Map the columns and add the join condition.
     *
     * @param on the condition
     */
    public void mapAndAddFilter(Expression on) {
        on.mapColumns(this, 0);
        addFilterCondition(on, true);
        on.createIndexConditions(session, this);
        if (nestedJoin != null) {
            on.mapColumns(nestedJoin, 0);
            on.createIndexConditions(session, nestedJoin);
        }
        if (join != null) {
            join.mapAndAddFilter(on);
        }
    }

    public TableFilter getJoin() {
        return join;
    }

    /**
     * Whether this is an outer joined table.
     *
     * @return true if it is
     */
    public boolean isJoinOuter() {
        return joinOuter;
    }

    /**
     * Whether this is indirectly an outer joined table (nested within an inner
     * join).
     *
     * @return true if it is
     */
    public boolean isJoinOuterIndirect() {
        return joinOuterIndirect;
    }

    /**
     * Get the query execution plan text to use for this table filter.
     *
     * @param isJoin if this is a joined table
     * @return the SQL statement snippet
     */
    public String getPlanSQL(boolean isJoin) {
        StringBuilder buff = new StringBuilder();
        if (isJoin) {
            if (joinOuter) {
                buff.append("LEFT OUTER JOIN ");
            } else {
                buff.append("INNER JOIN ");
            }
        }
        if (nestedJoin != null) {
            StringBuffer buffNested = new StringBuffer();
            TableFilter n = nestedJoin;
            do {
                buffNested.append(n.getPlanSQL(n != nestedJoin));
                buffNested.append('\n');
                n = n.getJoin();
            } while (n != null);
            String nested = buffNested.toString();
            boolean enclose = !nested.startsWith("(");
            if (enclose) {
                buff.append("(\n");
            }
            buff.append(StringUtils.indent(nested, 4, false));
            if (enclose) {
                buff.append(')');
            }
            if (isJoin) {
                buff.append(" ON ");
                if (joinCondition == null) {
                    // need to have a ON expression,
                    // otherwise the nesting is unclear
                    buff.append("1=1");
                } else {
                    buff.append(StringUtils.unEnclose(joinCondition.getSQL()));
                }
            }
            return buff.toString();
        }
        buff.append(table.getSQL());
        if (alias != null) {
            buff.append(' ').append(Parser.quoteIdentifier(alias));
        }
        if (index != null) {
            buff.append('\n');
            StatementBuilder planBuff = new StatementBuilder();
            if (joinBatch != null) {
                IndexLookupBatch lookupBatch = joinBatch.getLookupBatch(joinFilterId);
                if (lookupBatch == null) {
                    if (joinFilterId != 0) {
                        throw DbException.throwInternalError();
                    }
                } else {
                    planBuff.append("batched:");
                    String batchPlan = lookupBatch.getPlanSQL();
                    planBuff.append(batchPlan);
                    planBuff.append(" ");
                }
            }
            planBuff.append(index.getPlanSQL());
            if (indexConditions.size() > 0) {
                planBuff.append(": ");
                for (IndexCondition condition : indexConditions) {
                    planBuff.appendExceptFirst("\n    AND ");
                    planBuff.append(condition.getSQL());
                }
            }
            String plan = StringUtils.quoteRemarkSQL(planBuff.toString());
            if (plan.indexOf('\n') >= 0) {
                plan += "\n";
            }
            buff.append(StringUtils.indent("/* " + plan + " */", 4, false));
        }
        if (isJoin) {
            buff.append("\n    ON ");
            if (joinCondition == null) {
                // need to have a ON expression, otherwise the nesting is
                // unclear
                buff.append("1=1");
            } else {
                buff.append(StringUtils.unEnclose(joinCondition.getSQL()));
            }
        }
        if (filterCondition != null) {
            buff.append('\n');
            String condition = StringUtils.unEnclose(filterCondition.getSQL());
            condition = "/* WHERE " + StringUtils.quoteRemarkSQL(condition) + "\n*/";
            buff.append(StringUtils.indent(condition, 4, false));
        }
        if (scanCount > 0) {
            buff.append("\n    /* scanCount: ").append(scanCount).append(" */");
        }
        return buff.toString();
    }

    /**
     * Remove all index conditions that are not used by the current index.
     */
    void removeUnusableIndexConditions() {
        // the indexConditions list may be modified here
        for (int i = 0; i < indexConditions.size(); i++) {
            IndexCondition cond = indexConditions.get(i);
            if (!cond.isEvaluatable()) {
                indexConditions.remove(i--);
            }
        }
    }

    public int[] getMasks() {
        return masks;
    }

    public ArrayList<IndexCondition> getIndexConditions() {
        return indexConditions;
    }

    public Index getIndex() {
        return index;
    }

    public void setIndex(Index index) {
        this.index = index;
        cursor.setIndex(index);
    }

    public void setUsed(boolean used) {
        this.used = used;
    }

    public boolean isUsed() {
        return used;
    }

    /**
     * Set the session of this table filter.
     *
     * @param session the new session
     */
    void setSession(Session session) {
        this.session = session;
    }

    /**
     * Remove the joined table
     */
    public void removeJoin() {
        this.join = null;
    }

    public Expression getJoinCondition() {
        return joinCondition;
    }

    /**
     * Remove the join condition.
     */
    public void removeJoinCondition() {
        this.joinCondition = null;
    }

    public Expression getFilterCondition() {
        return filterCondition;
    }

    /**
     * Remove the filter condition.
     */
    public void removeFilterCondition() {
        this.filterCondition = null;
    }

    public void setFullCondition(Expression condition) {
        this.fullCondition = condition;
        if (join != null) {
            join.setFullCondition(condition);
        }
    }

    /**
     * Optimize the full condition. This will add the full condition to the
     * filter condition.
     *
     * @param fromOuterJoin if this method was called from an outer joined table
     */
    void optimizeFullCondition(boolean fromOuterJoin) {
        if (fullCondition != null) {
            fullCondition.addFilterConditions(this, fromOuterJoin || joinOuter);
            if (nestedJoin != null) {
                nestedJoin.optimizeFullCondition(fromOuterJoin || joinOuter);
            }
            if (join != null) {
                join.optimizeFullCondition(fromOuterJoin || joinOuter);
            }
        }
    }

    /**
     * Update the filter and join conditions of this and all joined tables with
     * the information that the given table filter and all nested filter can now
     * return rows or not.
     *
     * @param filter the table filter
     * @param b the new flag
     */
    public void setEvaluatable(TableFilter filter, boolean b) {
        filter.setEvaluatable(b);
        if (filterCondition != null) {
            filterCondition.setEvaluatable(filter, b);
        }
        if (joinCondition != null) {
            joinCondition.setEvaluatable(filter, b);
        }
        if (nestedJoin != null) {
            // don't enable / disable the nested join filters
            // if enabling a filter in a joined filter
            if (this == filter) {
                nestedJoin.setEvaluatable(nestedJoin, b);
            }
        }
        if (join != null) {
            join.setEvaluatable(filter, b);
        }
    }

    public void setEvaluatable(boolean evaluatable) {
        this.evaluatable = evaluatable;
    }

    @Override
    public String getSchemaName() {
        return table.getSchema().getName();
    }

    @Override
    public Column[] getColumns() {
        return table.getColumns();
    }

    /**
     * Get the system columns that this table understands. This is used for
     * compatibility with other databases. The columns are only returned if the
     * current mode supports system columns.
     *
     * @return the system columns
     */
    @Override
    public Column[] getSystemColumns() {
        if (!session.getDatabase().getMode().systemColumns) {
            return null;
        }
        Column[] sys = new Column[3];
        sys[0] = new Column("oid", Value.INT);
        sys[0].setTable(table, 0);
        sys[1] = new Column("ctid", Value.STRING);
        sys[1].setTable(table, 0);
        sys[2] = new Column("CTID", Value.STRING);
        sys[2].setTable(table, 0);
        return sys;
    }

    @Override
    public Column getRowIdColumn() {
        if (session.getDatabase().getSettings().rowId) {
            return table.getRowIdColumn();
        }
        return null;
    }

    @Override
    public Value getValue(Column column) {
        if (joinBatch != null) {
            return joinBatch.getValue(joinFilterId, column);
        }
        if (currentSearchRow == null) {
            return null;
        }
        int columnId = column.getColumnId();
        if (columnId == -1) {
            return ValueLong.get(currentSearchRow.getKey());
        }
        if (current == null) {
            Value v = currentSearchRow.getValue(columnId);
            if (v != null) {
                return v;
            }
            current = cursor.get();
            if (current == null) {
                return ValueNull.INSTANCE;
            }
        }
        return current.getValue(columnId);
    }

    @Override
    public TableFilter getTableFilter() {
        return this;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    @Override
    public Expression optimize(ExpressionColumn expressionColumn, Column column) {
        return expressionColumn;
    }

    @Override
    public String toString() {
        return alias != null ? alias : table.toString();
    }

    /**
     * Add a column to the natural join key column list.
     *
     * @param c the column to add
     */
    public void addNaturalJoinColumn(Column c) {
        if (naturalJoinColumns == null) {
            naturalJoinColumns = New.arrayList();
        }
        naturalJoinColumns.add(c);
    }

    /**
     * Check if the given column is a natural join column.
     *
     * @param c the column to check
     * @return true if this is a joined natural join column
     */
    public boolean isNaturalJoinColumn(Column c) {
        return naturalJoinColumns != null && naturalJoinColumns.contains(c);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    /**
     * Are there any index conditions that involve IN(...).
     *
     * @return whether there are IN(...) comparisons
     */
    public boolean hasInComparisons() {
        for (IndexCondition cond : indexConditions) {
            int compareType = cond.getCompareType();
            if (compareType == Comparison.IN_QUERY || compareType == Comparison.IN_LIST) {
                return true;
            }
        }
        return false;
    }

    /**
     * Add the current row to the array, if there is a current row.
     *
     * @param rows the rows to lock
     */
    public void lockRowAdd(ArrayList<Row> rows) {
        if (state == FOUND) {
            rows.add(get());
        }
    }

    /**
     * Lock the given rows.
     *
     * @param forUpdateRows the rows to lock
     */
    public void lockRows(ArrayList<Row> forUpdateRows) {
        for (Row row : forUpdateRows) {
            Row newRow = row.getCopy();
            table.removeRow(session, row);
            session.log(table, UndoLogRecord.DELETE, row);
            table.addRow(session, newRow);
            session.log(table, UndoLogRecord.INSERT, newRow);
        }
    }

    public TableFilter getNestedJoin() {
        return nestedJoin;
    }

    /**
     * Visit this and all joined or nested table filters.
     *
     * @param visitor the visitor
     */
    public void visit(TableFilterVisitor visitor) {
        TableFilter f = this;
        do {
            visitor.accept(f);
            TableFilter n = f.nestedJoin;
            if (n != null) {
                n.visit(visitor);
            }
            f = f.join;
        } while (f != null);
    }

    public boolean isEvaluatable() {
        return evaluatable;
    }

    public Session getSession() {
        return session;
    }

    /**
     * A visitor for table filters.
     */
    public interface TableFilterVisitor {

        /**
         * This method is called for each nested or joined table filter.
         *
         * @param f the filter
         */
        void accept(TableFilter f);
    }
}
