/*
 * Copyright 2016-2018 shardingsphere.io.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package io.shardingsphere.core.routing.router.sharding;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import io.shardingsphere.api.algorithm.sharding.ListShardingValue;
import io.shardingsphere.api.algorithm.sharding.ShardingValue;
import io.shardingsphere.core.constant.DatabaseType;
import io.shardingsphere.core.constant.ShardingOperator;
import io.shardingsphere.core.metadata.datasource.ShardingDataSourceMetaData;
import io.shardingsphere.core.metadata.table.ShardingTableMetaData;
import io.shardingsphere.core.optimizer.OptimizeEngineFactory;
import io.shardingsphere.core.optimizer.condition.ShardingCondition;
import io.shardingsphere.core.optimizer.condition.ShardingConditions;
import io.shardingsphere.core.parsing.SQLParsingEngine;
import io.shardingsphere.core.parsing.antlr.sql.statement.dcl.DCLStatement;
import io.shardingsphere.core.parsing.antlr.sql.statement.ddl.DDLStatement;
import io.shardingsphere.core.parsing.parser.context.condition.AndCondition;
import io.shardingsphere.core.parsing.parser.context.condition.Column;
import io.shardingsphere.core.parsing.parser.context.condition.Condition;
import io.shardingsphere.core.parsing.parser.context.condition.GeneratedKeyCondition;
import io.shardingsphere.core.parsing.parser.dialect.mysql.statement.ShowDatabasesStatement;
import io.shardingsphere.core.parsing.parser.dialect.mysql.statement.ShowTableStatusStatement;
import io.shardingsphere.core.parsing.parser.dialect.mysql.statement.ShowTablesStatement;
import io.shardingsphere.core.parsing.parser.dialect.mysql.statement.UseStatement;
import io.shardingsphere.core.parsing.parser.dialect.postgresql.statement.ResetParamStatement;
import io.shardingsphere.core.parsing.parser.dialect.postgresql.statement.SetParamStatement;
import io.shardingsphere.core.parsing.parser.sql.SQLStatement;
import io.shardingsphere.core.parsing.parser.sql.dal.DALStatement;
import io.shardingsphere.core.parsing.parser.sql.dml.insert.InsertStatement;
import io.shardingsphere.core.parsing.parser.sql.dql.select.SelectStatement;
import io.shardingsphere.core.rewrite.SQLBuilder;
import io.shardingsphere.core.rewrite.SQLRewriteEngine;
import io.shardingsphere.core.routing.RouteUnit;
import io.shardingsphere.core.routing.SQLRouteResult;
import io.shardingsphere.core.routing.type.RoutingEngine;
import io.shardingsphere.core.routing.type.RoutingResult;
import io.shardingsphere.core.routing.type.TableUnit;
import io.shardingsphere.core.routing.type.broadcast.DatabaseBroadcastRoutingEngine;
import io.shardingsphere.core.routing.type.broadcast.InstanceBroadcastRoutingEngine;
import io.shardingsphere.core.routing.type.broadcast.TableBroadcastRoutingEngine;
import io.shardingsphere.core.routing.type.complex.ComplexRoutingEngine;
import io.shardingsphere.core.routing.type.defaultdb.DefaultDatabaseRoutingEngine;
import io.shardingsphere.core.routing.type.ignore.IgnoreRoutingEngine;
import io.shardingsphere.core.routing.type.standard.StandardRoutingEngine;
import io.shardingsphere.core.routing.type.unicast.UnicastRoutingEngine;
import io.shardingsphere.core.rule.BindingTableRule;
import io.shardingsphere.core.rule.ShardingRule;
import io.shardingsphere.core.rule.TableRule;
import io.shardingsphere.core.util.SQLLogger;
import io.shardingsphere.spi.parsing.ParsingHook;
import io.shardingsphere.spi.parsing.SPIParsingHook;
import lombok.RequiredArgsConstructor;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

/**
 * Sharding router with parse.
 *
 * @author zhangliang
 * @author maxiaoguang
 * @author panjuan
 */
@RequiredArgsConstructor
public final class ParsingSQLRouter implements ShardingRouter {
    
    private final ShardingRule shardingRule;
    
    private final ShardingTableMetaData shardingTableMetaData;
    
    private final DatabaseType databaseType;
    
    private final boolean showSQL;
    
    private final List<Number> generatedKeys = new LinkedList<>();
    
    private final ShardingDataSourceMetaData shardingDataSourceMetaData;
    
    private final ParsingHook parsingHook = new SPIParsingHook();
    
    @Override
    public SQLStatement parse(final String logicSQL, final boolean useCache) {
        parsingHook.start(logicSQL);
        try {
            SQLStatement result = new SQLParsingEngine(databaseType, logicSQL, shardingRule, shardingTableMetaData).parse(useCache);
            parsingHook.finishSuccess();
            return result;
        } catch (final Exception ex) {
            parsingHook.finishFailure(ex);
            throw ex;
        }
    }
    
    @Override
    public SQLRouteResult route(final String logicSQL, final List<Object> parameters, final SQLStatement sqlStatement) {
        Optional<GeneratedKey> generatedKey = sqlStatement instanceof InsertStatement ? getGenerateKey(shardingRule, (InsertStatement) sqlStatement, parameters) : Optional.<GeneratedKey>absent();
        SQLRouteResult result = new SQLRouteResult(sqlStatement, generatedKey.orNull());
        ShardingConditions shardingConditions = OptimizeEngineFactory.newInstance(shardingRule, sqlStatement, parameters, generatedKey.orNull()).optimize();
        if (generatedKey.isPresent()) {
            setGeneratedKeys(result, generatedKey.get());
        }
        if (sqlStatement instanceof SelectStatement) {
            checkAndMergeShardingValue((SelectStatement) sqlStatement, shardingConditions);
        }
        RoutingResult routingResult = doRoute(sqlStatement, shardingConditions);
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule, logicSQL, databaseType, sqlStatement, shardingConditions, parameters);
        if (sqlStatement instanceof SelectStatement && null != ((SelectStatement) sqlStatement).getLimit()) {
            processLimit(parameters, (SelectStatement) sqlStatement);
        }
        SQLBuilder sqlBuilder = rewriteEngine.rewrite(!routingResult.isSingleRouting());
        for (TableUnit each : routingResult.getTableUnits().getTableUnits()) {
            result.getRouteUnits().add(new RouteUnit(each.getDataSourceName(), rewriteEngine.generateSQL(each, sqlBuilder, shardingDataSourceMetaData)));
        }
        if (showSQL) {
            SQLLogger.logSQL(logicSQL, sqlStatement, result.getRouteUnits());
        }
        return result;
    }
    
    private Optional<GeneratedKey> getGenerateKey(final ShardingRule shardingRule, final InsertStatement insertStatement, final List<Object> parameters) {
        GeneratedKey result = null;
        if (-1 != insertStatement.getGenerateKeyColumnIndex()) {
            for (GeneratedKeyCondition generatedKeyCondition : insertStatement.getGeneratedKeyConditions()) {
                if (null == result) {
                    result = new GeneratedKey(generatedKeyCondition.getColumn());
                }
                if (-1 == generatedKeyCondition.getIndex()) {
                    result.getGeneratedKeys().add(generatedKeyCondition.getValue());
                } else {
                    result.getGeneratedKeys().add((Number) parameters.get(generatedKeyCondition.getIndex()));
                }
            }
            return Optional.fromNullable(result);
        }
        String logicTableName = insertStatement.getTables().getSingleTableName();
        Optional<TableRule> tableRule = shardingRule.findTableRuleByLogicTable(logicTableName);
        if (!tableRule.isPresent()) {
            return Optional.absent();
        }
        Optional<Column> generateKeyColumn = shardingRule.getGenerateKeyColumn(logicTableName);
        if (generateKeyColumn.isPresent()) {
            result = new GeneratedKey(generateKeyColumn.get());
            for (int i = 0; i < insertStatement.getInsertValues().getInsertValues().size(); i++) {
                result.getGeneratedKeys().add(shardingRule.generateKey(logicTableName));
            }
        }
        return Optional.fromNullable(result);
    }
    
    private void setGeneratedKeys(final SQLRouteResult sqlRouteResult, final GeneratedKey generatedKey) {
        generatedKeys.addAll(generatedKey.getGeneratedKeys());
        sqlRouteResult.getGeneratedKey().getGeneratedKeys().clear();
        sqlRouteResult.getGeneratedKey().getGeneratedKeys().addAll(generatedKeys);
    }
    
    private void checkAndMergeShardingValue(final SelectStatement selectStatement, final ShardingConditions shardingConditions) {
        if (selectStatement.getSubQueryStatements().isEmpty() || selectStatement.getTables().isEmpty()) {
            return;
        }
        Preconditions.checkState(!shardingConditions.getShardingConditions().isEmpty(), "Must have sharding column with subquery.");
        for (AndCondition each : selectStatement.getConditions().getOrCondition().getAndConditions()) {
            for (Condition eachCondition : each.getConditions()) {
                Preconditions.checkState(ShardingOperator.EQUAL == eachCondition.getOperator(), "Only support sharding by '=' with subquery.");
            }
        }
        ShardingCondition firstShardingCondition = shardingConditions.getShardingConditions().get(0);
        Preconditions.checkState(isListShardingValue(firstShardingCondition), "Only support sharding by '=' with subquery.");
        for (ShardingCondition each : shardingConditions.getShardingConditions()) {
            Preconditions.checkState(isSameShardingCondition(firstShardingCondition, each), "Sharding value must same with subquery.");
        }
        shardingConditions.getShardingConditions().clear();
        shardingConditions.getShardingConditions().add(firstShardingCondition);
    }
    
    private boolean isListShardingValue(final ShardingCondition shardingCondition) {
        for (ShardingValue each : shardingCondition.getShardingValues()) {
            if (!(each instanceof ListShardingValue)) {
                return false;
            }
        }
        return true;
    }
    
    private boolean isSameShardingCondition(final ShardingCondition shardingCondition1, final ShardingCondition shardingCondition2) {
        if (shardingCondition1.getShardingValues().size() != shardingCondition2.getShardingValues().size()) {
            return false;
        }
        for (int i = 0; i < shardingCondition1.getShardingValues().size(); i++) {
            ShardingValue shardingValue1 = shardingCondition1.getShardingValues().get(i);
            ShardingValue shardingValue2 = shardingCondition2.getShardingValues().get(i);
            Preconditions.checkArgument(shardingValue1 instanceof ListShardingValue, "Only support sharding by '=' with subquery.");
            Preconditions.checkArgument(shardingValue2 instanceof ListShardingValue, "Only support sharding by '=' with subquery.");
            if (!isSameShardingValue((ListShardingValue) shardingValue1, (ListShardingValue) shardingValue2)) {
                return false;
            }
        }
        return true;
    }
    
    private boolean isSameShardingValue(final ListShardingValue shardingValue1, final ListShardingValue shardingValue2) {
        return isSameLogicTable(shardingValue1, shardingValue2) 
                && shardingValue1.getColumnName().equals(shardingValue2.getColumnName()) && shardingValue1.getValues().equals(shardingValue2.getValues());
    }
    
    private boolean isSameLogicTable(final ListShardingValue shardingValue1, final ListShardingValue shardingValue2) {
        return shardingValue1.getLogicTableName().equals(shardingValue2.getLogicTableName()) || isBindingTable(shardingValue1, shardingValue2);
    }
    
    private boolean isBindingTable(final ListShardingValue shardingValue1, final ListShardingValue shardingValue2) {
        Optional<BindingTableRule> bindingRule = shardingRule.findBindingTableRule(shardingValue1.getLogicTableName());
        return bindingRule.isPresent() && bindingRule.get().hasLogicTable(shardingValue2.getLogicTableName());
    }
    
    private RoutingResult doRoute(final SQLStatement sqlStatement, final ShardingConditions shardingConditions) {
        Collection<String> tableNames = sqlStatement.getTables().getTableNames();
        RoutingEngine routingEngine;
        if (sqlStatement instanceof UseStatement) {
            routingEngine = new IgnoreRoutingEngine();
        } else if (shardingRule.isAllBroadcastTables(tableNames) && !(sqlStatement instanceof SelectStatement)) {
            routingEngine = new DatabaseBroadcastRoutingEngine(shardingRule);
        } else if (sqlStatement instanceof DDLStatement || (sqlStatement instanceof DCLStatement && ((DCLStatement) sqlStatement).isGrantForSingleTable())) {
            routingEngine = new TableBroadcastRoutingEngine(shardingRule, sqlStatement);
        } else if (sqlStatement instanceof ShowDatabasesStatement || ((sqlStatement instanceof ShowTablesStatement || sqlStatement instanceof ShowTableStatusStatement) && tableNames.isEmpty())
                || sqlStatement instanceof SetParamStatement || sqlStatement instanceof ResetParamStatement) {
            routingEngine = new DatabaseBroadcastRoutingEngine(shardingRule);
        } else if (sqlStatement instanceof DCLStatement) {
            routingEngine = new InstanceBroadcastRoutingEngine(shardingRule, shardingDataSourceMetaData);
        } else if (shardingRule.isAllInDefaultDataSource(tableNames)) {
            routingEngine = new DefaultDatabaseRoutingEngine(shardingRule, tableNames);
        } else if (shardingConditions.isAlwaysFalse()) {
            routingEngine = new UnicastRoutingEngine(shardingRule, tableNames);
        } else if (sqlStatement instanceof DALStatement) {
            routingEngine = new UnicastRoutingEngine(shardingRule, tableNames);
        } else if (tableNames.isEmpty() && sqlStatement instanceof SelectStatement || shardingRule.isAllBroadcastTables(tableNames) && sqlStatement instanceof SelectStatement) {
            routingEngine = new UnicastRoutingEngine(shardingRule, tableNames);
        } else if (tableNames.isEmpty()) {
            routingEngine = new DatabaseBroadcastRoutingEngine(shardingRule);
        } else if (1 == tableNames.size() || shardingRule.isAllBindingTables(tableNames)) {
            routingEngine = new StandardRoutingEngine(shardingRule, tableNames.iterator().next(), shardingConditions);
        } else {
            // TODO config for cartesian set
            routingEngine = new ComplexRoutingEngine(shardingRule, tableNames, shardingConditions);
        }
        return routingEngine.route();
    }
    
    private void processLimit(final List<Object> parameters, final SelectStatement selectStatement) {
        boolean isNeedFetchAll = (!selectStatement.getGroupByItems().isEmpty() || !selectStatement.getAggregationSelectItems().isEmpty()) && !selectStatement.isSameGroupByAndOrderByItems();
        selectStatement.getLimit().processParameters(parameters, isNeedFetchAll);
    }
}
