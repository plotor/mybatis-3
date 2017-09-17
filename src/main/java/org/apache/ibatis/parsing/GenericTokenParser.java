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

package org.apache.ibatis.parsing;

/**
 * 通用占位符解析器
 *
 * @author Clinton Begin
 */
public class GenericTokenParser {

    /** 占位符起始标记 */
    private final String openToken;

    /** 占位符结束标记 */
    private final String closeToken;

    /** 占位符解析器 */
    private final TokenHandler handler;

    public GenericTokenParser(String openToken, String closeToken, TokenHandler handler) {
        this.openToken = openToken;
        this.closeToken = closeToken;
        this.handler = handler;
    }

    /**
     * 占位符解析
     * 遍历寻找指定的占位符变量，交由指定的 handler 进行解析
     *
     * @param text
     * @return
     */
    public String parse(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // 寻找起始占位符
        int start = text.indexOf(openToken, 0);
        if (start == -1) {
            return text;
        }
        char[] src = text.toCharArray();
        int offset = 0;
        final StringBuilder builder = new StringBuilder(); // 记录解析后的字符串
        StringBuilder expression = null; // 记录占位符变量
        while (start > -1) {
            if (start > 0 && src[start - 1] == '\\') {
                // 起始标记被转义，移除反斜杠
                builder.append(src, offset, start - offset - 1).append(openToken);
                offset = start + openToken.length();
            } else {
                // 找到起始占位符，寻找结束占位符
                if (expression == null) {
                    expression = new StringBuilder();
                } else {
                    expression.setLength(0);
                }
                builder.append(src, offset, start - offset);
                offset = start + openToken.length();
                int end = text.indexOf(closeToken, offset);
                while (end > -1) {
                    if (end > offset && src[end - 1] == '\\') {
                        // this close token is escaped. remove the backslash and continue.
                        expression.append(src, offset, end - offset - 1).append(closeToken);
                        offset = end + closeToken.length();
                        end = text.indexOf(closeToken, offset);
                    } else {
                        expression.append(src, offset, end - offset);
                        offset = end + closeToken.length();
                        break;
                    }
                }
                if (end == -1) {
                    // close token was not found.
                    builder.append(src, start, src.length - start);
                    offset = src.length;
                } else {
                    // 将解析到的占位符变量交由指定的 handler 进解析
                    builder.append(handler.handleToken(expression.toString()));
                    offset = end + closeToken.length();
                }
            }
            start = text.indexOf(openToken, offset);
        }
        if (offset < src.length) {
            builder.append(src, offset, src.length - offset);
        }
        // 返回解析后的完整字符串
        return builder.toString();
    }

    public static void main(String[] args) {
        GenericTokenParser parser = new GenericTokenParser("${", "}", null);
        parser.parse("Hello, this is ${username}, and my age is ${age}");
    }
}
