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
package org.apache.rocketmq.streams.common.functions;

import java.io.Serializable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public interface TableSplitFunction extends Serializable {

    /**
     * 设置分片数， 分片逻辑select count(1) as c,min(id) min, max(id) as max from table where a>b
     *
     * @return select * from table where id>=min_batchsize and id< max_batchsize and midify_tiem>'#{modifyTime=1900=01-01 00:00:00}' order by modifyTime
     */
    List<Split> split();

    abstract class Split implements Serializable {
        int splitCount;
        String splitSQL;
        int start_index;
        int endIndex;
        String splitName;

        public abstract Iterator<Map<String, Object>> iterator();

        public String getSplitName() {
            return splitName;
        }
    }
}
