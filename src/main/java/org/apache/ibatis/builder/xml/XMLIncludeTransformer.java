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

package org.apache.ibatis.builder.xml;

import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * @author Frank D. Martinez [mnesarco]
 */
public class XMLIncludeTransformer {

    private final Configuration configuration;
    private final MapperBuilderAssistant builderAssistant;

    public XMLIncludeTransformer(Configuration configuration, MapperBuilderAssistant builderAssistant) {
        this.configuration = configuration;
        this.builderAssistant = builderAssistant;
    }

    /**
     * 将 <include/> 节点替换成 <sql/> 节点中定义的 SQL，同时解析 ‘${xxx}’ 占位符
     *
     * @param source
     */
    public void applyIncludes(Node source) {
        Properties variablesContext = new Properties();
        Properties configurationVariables = configuration.getVariables();
        if (configurationVariables != null) {
            variablesContext.putAll(configurationVariables);
        }
        this.applyIncludes(source, variablesContext, false);
    }

    /**
     * Recursively apply includes through all SQL fragments.
     *
     * @param source Include node in DOM tree
     * @param variablesContext Current context for static variables with values
     */
    private void applyIncludes(Node source, final Properties variablesContext, boolean included) {
        if (source.getNodeName().equals("include")) { // 如果是 <include /> 子节点
            // 查找 refid 属性指向的 <sql/> 节点， 返回节点的 Node 对象（深拷贝）
            Node toInclude = this.findSqlFragment(this.getStringAttribute(source, "refid"), variablesContext);
            // 获取 <include/> 下的 <property/> 属性，封装进 variablesContext 返回
            Properties toIncludeContext = this.getVariablesContext(source, variablesContext);
            // 递归处理
            this.applyIncludes(toInclude, toIncludeContext, true);
            if (toInclude.getOwnerDocument() != source.getOwnerDocument()) {
                toInclude = source.getOwnerDocument().importNode(toInclude, true);
            }
            // 替换 <include/> 节点为 <sql/> 节点
            source.getParentNode().replaceChild(toInclude, source);
            while (toInclude.hasChildNodes()) {
                // 将 <sql/> 的子节点添加到 <sql/> 节点的前面
                toInclude.getParentNode().insertBefore(toInclude.getFirstChild(), toInclude);
            }
            // 删除 <sql/> 节点
            toInclude.getParentNode().removeChild(toInclude);
        } else if (source.getNodeType() == Node.ELEMENT_NODE) {
            // 遍历处理当前 SQL 语句的子节点
            NodeList children = source.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                this.applyIncludes(children.item(i), variablesContext, included);
            }
        } else if (included && source.getNodeType() == Node.TEXT_NODE && !variablesContext.isEmpty()) {
            // 替换占位符为对应的变量
            source.setNodeValue(PropertyParser.parse(source.getNodeValue(), variablesContext));
        }
    }

    /**
     *
     * @param refid
     * @param variables
     * @return
     */
    private Node findSqlFragment(String refid, Properties variables) {
        refid = PropertyParser.parse(refid, variables);
        refid = builderAssistant.applyCurrentNamespace(refid, true);
        try {
            XNode nodeToInclude = configuration.getSqlFragments().get(refid);
            return nodeToInclude.getNode().cloneNode(true);
        } catch (IllegalArgumentException e) {
            throw new IncompleteElementException("Could not find SQL statement to include with refid '" + refid + "'", e);
        }
    }

    private String getStringAttribute(Node node, String name) {
        return node.getAttributes().getNamedItem(name).getNodeValue();
    }

    /**
     * Read placeholders and their values from include node definition.
     *
     * @param node Include node instance
     * @param inheritedVariablesContext Current context used for replace variables in new variables values
     * @return variables context from include instance (no inherited values)
     */
    private Properties getVariablesContext(Node node, Properties inheritedVariablesContext) {
        Map<String, String> declaredProperties = null;
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                String name = getStringAttribute(n, "name");
                // Replace variables inside
                String value = PropertyParser.parse(getStringAttribute(n, "value"), inheritedVariablesContext);
                if (declaredProperties == null) {
                    declaredProperties = new HashMap<String, String>();
                }
                if (declaredProperties.put(name, value) != null) {
                    throw new BuilderException("Variable " + name + " defined twice in the same include definition");
                }
            }
        }
        if (declaredProperties == null) {
            return inheritedVariablesContext;
        } else {
            Properties newProperties = new Properties();
            newProperties.putAll(inheritedVariablesContext);
            newProperties.putAll(declaredProperties);
            return newProperties;
        }
    }
}
