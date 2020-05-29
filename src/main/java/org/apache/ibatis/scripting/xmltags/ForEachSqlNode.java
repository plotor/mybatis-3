/**
 * Copyright 2009-2019 the original author or authors.
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

import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.session.Configuration;

import java.util.Map;

/**
 * @author Clinton Begin
 */
public class ForEachSqlNode implements SqlNode {

    public static final String ITEM_PREFIX = "__frch_";

    private final ExpressionEvaluator evaluator;
    private final String collectionExpression;
    private final SqlNode contents;
    private final String open;
    private final String close;
    private final String separator;
    private final String item;
    private final String index;
    private final Configuration configuration;

    public ForEachSqlNode(Configuration configuration, SqlNode contents, String collectionExpression, String index, String item, String open, String close, String separator) {
        this.evaluator = new ExpressionEvaluator();
        this.collectionExpression = collectionExpression;
        this.contents = contents;
        this.open = open;
        this.close = close;
        this.separator = separator;
        this.index = index;
        this.item = item;
        this.configuration = configuration;
    }

    @Override
    public boolean apply(DynamicContext context) {
        Map<String, Object> bindings = context.getBindings();
        // 解析集合 OGNL 表达式对应的值，返回值对应的迭代器
        final Iterable<?> iterable = evaluator.evaluateIterable(collectionExpression, bindings);
        if (!iterable.iterator().hasNext()) {
            return true;
        }
        boolean first = true;
        // 添加 open 前缀标识
        this.applyOpen(context);
        int i = 0;
        // 迭代处理集合
        for (Object o : iterable) {
            // 备份一下上下文对象
            DynamicContext oldContext = context;
            // 第一次遍历，或未指定分隔符
            if (first || separator == null) {
                context = new PrefixedContext(context, "");
            }
            // 其它情况
            else {
                context = new PrefixedContext(context, separator);
            }
            int uniqueNumber = context.getUniqueNumber();
            // 如果是 Map 类型，将 key 和 value 记录到 DynamicContext#bindings 属性中
            if (o instanceof Map.Entry) {
                @SuppressWarnings("unchecked")
                Map.Entry<Object, Object> mapEntry = (Map.Entry<Object, Object>) o;
                this.applyIndex(context, mapEntry.getKey(), uniqueNumber);
                this.applyItem(context, mapEntry.getValue(), uniqueNumber);
            }
            // 将当前索引值和元素记录到 DynamicContext#bindings 属性中
            else {
                this.applyIndex(context, i, uniqueNumber);
                this.applyItem(context, o, uniqueNumber);
            }
            // 应用子标签的 apply 方法
            contents.apply(new FilteredDynamicContext(configuration, context, index, item, uniqueNumber));
            if (first) {
                first = !((PrefixedContext) context).isPrefixApplied();
            }
            // 恢复上下文对象
            context = oldContext;
            i++;
        }
        // 添加 close 后缀标识
        this.applyClose(context);
        context.getBindings().remove(item);
        context.getBindings().remove(index);
        return true;
    }

    private void applyIndex(DynamicContext context, Object o, int i) {
        if (index != null) {
            context.bind(index, o);
            context.bind(itemizeItem(index, i), o);
        }
    }

    private void applyItem(DynamicContext context, Object o, int i) {
        if (item != null) {
            context.bind(item, o);
            context.bind(itemizeItem(item, i), o);
        }
    }

    private void applyOpen(DynamicContext context) {
        if (open != null) {
            context.appendSql(open);
        }
    }

    private void applyClose(DynamicContext context) {
        if (close != null) {
            context.appendSql(close);
        }
    }

    private static String itemizeItem(String item, int i) {
        // 返回 __frch_item_i 的形式
        return ITEM_PREFIX + item + "_" + i;
    }

    private static class FilteredDynamicContext extends DynamicContext {
        private final DynamicContext delegate;
        private final int index;
        private final String itemIndex;
        private final String item;

        public FilteredDynamicContext(Configuration configuration, DynamicContext delegate, String itemIndex, String item, int i) {
            super(configuration, null);
            this.delegate = delegate;
            this.index = i;
            this.itemIndex = itemIndex;
            this.item = item;
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
        public String getSql() {
            return delegate.getSql();
        }

        @Override
        public void appendSql(String sql) {
            GenericTokenParser parser = new GenericTokenParser("#{", "}", content -> {
                // 替换 item 为 __frch_item_index
                String newContent = content.replaceFirst("^\\s*" + item + "(?![^.,:\\s])", itemizeItem(item, index));
                // 替换 itemIndex 为 __frch_itemIndex_index
                if (itemIndex != null && newContent.equals(content)) {
                    newContent = content.replaceFirst("^\\s*" + itemIndex + "(?![^.,:\\s])", itemizeItem(itemIndex, index));
                }
                // 追加 #{} 标识
                return "#{" + newContent + "}";
            });

            delegate.appendSql(parser.parse(sql));
        }

        @Override
        public int getUniqueNumber() {
            return delegate.getUniqueNumber();
        }

    }

    private class PrefixedContext extends DynamicContext {
        private final DynamicContext delegate;
        private final String prefix;
        private boolean prefixApplied;

        public PrefixedContext(DynamicContext delegate, String prefix) {
            super(configuration, null);
            this.delegate = delegate;
            this.prefix = prefix;
            this.prefixApplied = false;
        }

        public boolean isPrefixApplied() {
            return prefixApplied;
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
        public void appendSql(String sql) {
            if (!prefixApplied && sql != null && sql.trim().length() > 0) {
                delegate.appendSql(prefix);
                prefixApplied = true;
            }
            delegate.appendSql(sql);
        }

        @Override
        public String getSql() {
            return delegate.getSql();
        }

        @Override
        public int getUniqueNumber() {
            return delegate.getUniqueNumber();
        }
    }

}
