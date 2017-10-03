/**
 * Copyright 2009-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ibatis.scripting.xmltags;

import org.apache.ibatis.session.Configuration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;

/**
 * @author Clinton Begin
 */
public class TrimSqlNode implements SqlNode {

    /** 记录 <trim/> 节点的子节点 */
    private final SqlNode contents;

    /** 期望追加的前缀字符串 */
    private final String prefix;

    /** 期望追加的后缀字符串 */
    private final String suffix;

    /** 如果 <trim/> 包裹的 SQL 语句为空，则删除指定前缀 */
    private final List<String> prefixesToOverride;

    /** 如果 <trim/> 包裹的 SQL 语句为空，则删除指定后缀 */
    private final List<String> suffixesToOverride;

    private final Configuration configuration;

    public TrimSqlNode(
            Configuration configuration, SqlNode contents, String prefix,
            String prefixesToOverride, String suffix, String suffixesToOverride) {
        this(configuration, contents, prefix, parseOverrides(prefixesToOverride), suffix, parseOverrides(suffixesToOverride));
    }

    protected TrimSqlNode(
            Configuration configuration, SqlNode contents, String prefix,
            List<String> prefixesToOverride, String suffix, List<String> suffixesToOverride) {
        this.contents = contents;
        this.prefix = prefix;
        this.prefixesToOverride = prefixesToOverride;
        this.suffix = suffix;
        this.suffixesToOverride = suffixesToOverride;
        this.configuration = configuration;
    }

    @Override
    public boolean apply(DynamicContext context) {
        // 创建 FilteredDynamicContext 对象，封装上下文
        FilteredDynamicContext filteredDynamicContext = new FilteredDynamicContext(context);
        // 应用子节点的 apply 方法
        boolean result = contents.apply(filteredDynamicContext);
        // 处理前缀和后缀
        filteredDynamicContext.applyAll();
        return result;
    }

    private static List<String> parseOverrides(String overrides) {
        if (overrides != null) {
            final StringTokenizer parser = new StringTokenizer(overrides, "|", false);
            final List<String> list = new ArrayList<String>(parser.countTokens());
            while (parser.hasMoreTokens()) {
                list.add(parser.nextToken().toUpperCase(Locale.ENGLISH));
            }
            return list;
        }
        return Collections.emptyList();
    }

    private class FilteredDynamicContext extends DynamicContext {

        private DynamicContext delegate;

        /** 是否已经处理过前缀和后缀 */
        private boolean prefixApplied;
        private boolean suffixApplied;

        /** 用于记录子节点的解析结果 */
        private StringBuilder sqlBuffer;

        public FilteredDynamicContext(DynamicContext delegate) {
            super(configuration, null);
            this.delegate = delegate;
            this.prefixApplied = false;
            this.suffixApplied = false;
            this.sqlBuffer = new StringBuilder();
        }

        public void applyAll() {
            sqlBuffer = new StringBuilder(sqlBuffer.toString().trim());
            // 全部转换成大写
            String trimmedUppercaseSql = sqlBuffer.toString().toUpperCase(Locale.ENGLISH);
            if (trimmedUppercaseSql.length() > 0) {
                // 处理前缀
                this.applyPrefix(sqlBuffer, trimmedUppercaseSql);
                // 处理后缀
                this.applySuffix(sqlBuffer, trimmedUppercaseSql);
            }
            // 添加解析后的结果到 delegate 中
            delegate.appendSql(sqlBuffer.toString());
        }

        @Override
        public Map<String, Object> getBindings() {
            return delegate.getBindings();
        }

        @Override
        public void bind(String name, Object value) {
            delegate.bind(name, value);
        }

        @Override
        public int getUniqueNumber() {
            return delegate.getUniqueNumber();
        }

        @Override
        public void appendSql(String sql) {
            sqlBuffer.append(sql);
        }

        @Override
        public String getSql() {
            return delegate.getSql();
        }

        private void applyPrefix(StringBuilder sql, String trimmedUppercaseSql) {
            if (!prefixApplied) { // 如果前缀没有被处理过
                prefixApplied = true;
                if (prefixesToOverride != null) {
                    for (String toRemove : prefixesToOverride) {
                        // 移除包含的前缀
                        if (trimmedUppercaseSql.startsWith(toRemove)) {
                            sql.delete(0, toRemove.trim().length());
                            break;
                        }
                    }
                }
                // 插入指定前缀
                if (prefix != null) {
                    sql.insert(0, " ");
                    sql.insert(0, prefix);
                }
            }
        }

        private void applySuffix(StringBuilder sql, String trimmedUppercaseSql) {
            if (!suffixApplied) { // 如果后缀没有被处理过
                suffixApplied = true;
                if (suffixesToOverride != null) {
                    for (String toRemove : suffixesToOverride) {
                        if (trimmedUppercaseSql.endsWith(toRemove) || trimmedUppercaseSql.endsWith(toRemove.trim())) {
                            // 移除包含的后缀
                            int start = sql.length() - toRemove.trim().length();
                            int end = sql.length();
                            sql.delete(start, end);
                            break;
                        }
                    }
                }
                // 追加指定后缀
                if (suffix != null) {
                    sql.append(" ");
                    sql.append(suffix);
                }
            }
        }

    }

}
