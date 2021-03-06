/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.core.merge.dal.show;

import org.apache.shardingsphere.core.constant.ShardingConstant;
import org.apache.shardingsphere.core.execute.sql.execute.result.QueryResult;
import org.apache.shardingsphere.core.metadata.table.TableMetas;
import org.apache.shardingsphere.core.rule.ShardingRule;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Merged result for show tables.
 *
 * @author zhangliang
 * @author panjuan
 */
public final class ShowTablesMergedResult extends LogicTablesMergedResult {
    
    private static final Map<String, Integer> LABEL_AND_INDEX_MAP = new HashMap<>(1, 1);
    
    static {
        LABEL_AND_INDEX_MAP.put("Tables_in_" + ShardingConstant.LOGIC_SCHEMA_NAME, 1);
    }
    
    public ShowTablesMergedResult(final ShardingRule shardingRule, final List<QueryResult> queryResults, final TableMetas tableMetas) throws SQLException {
        super(LABEL_AND_INDEX_MAP, shardingRule, queryResults, tableMetas);
    }
    
    /**
     * Reset column label.
     * 
     * @param schema schema 
     */
    public void resetColumnLabel(final String schema) {
        Map<String, Integer> labelAndIndexMap = new HashMap<>(1, 1);
        labelAndIndexMap.put(schema, 1);
        resetLabelAndIndexMap(labelAndIndexMap);
    }
}
