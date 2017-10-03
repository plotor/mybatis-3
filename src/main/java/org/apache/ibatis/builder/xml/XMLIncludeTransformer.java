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
     * 将 <include/> 节点替换成 <sql/> 节点中定义的 SQL 语句片段，同时解析 ‘${xxx}’ 占位符
     *
     * @param source
     */
    public void applyIncludes(Node source) {
        Properties variablesContext = new Properties();
        // 获取配置文件中 <properties/> 节点配置的变量
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
        /*注意：最开始进入本方法时，source 变量对应的节点并不是 <include/> 节点，而是 <select/> 这类节点*/
        if (source.getNodeName().equals("include")) { // 处理 <include/> 节点
            // 获取 refid 指向的 <sql/> 节点对象的深拷贝
            Node toInclude = this.findSqlFragment(this.getStringAttribute(source, "refid"), variablesContext);
            // 获取 <include/> 下的 <property/> 属性，与 variablesContext 合并返回新的 Properties 对象
            Properties toIncludeContext = this.getVariablesContext(source, variablesContext);
            // 递归处理，这里的 included 参数为 true
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
            // 遍历处理当前 SQL 语句节点的子节点
            NodeList children = source.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                // 递归调用
                this.applyIncludes(children.item(i), variablesContext, included);
            }
        } else if (included && source.getNodeType() == Node.TEXT_NODE && !variablesContext.isEmpty()) {
            // 替换占位符为 variablesContext 中对应的配置值，这里替换的是引用 <sql/> 节点中定义的语句片段中对应的占位符
            source.setNodeValue(PropertyParser.parse(source.getNodeValue(), variablesContext));
        }
    }

    /**
     * 获取指定 id 对应的 <sql/> 节点的深拷贝
     *
     * @param refid
     * @param variables
     * @return
     */
    private Node findSqlFragment(String refid, Properties variables) {
        // 解析带有 ‘${}’ 占位符的字符串，将其中的占位符变量替换成 variables 中对应的属性值
        refid = PropertyParser.parse(refid, variables); // 注意：这里替换并不是 <sql/> 语句片段中的占位符
        refid = builderAssistant.applyCurrentNamespace(refid, true);
        try {
            // 从 Configuration.sqlFragments 中获取 id 对应的 <sql/> 节点
            XNode nodeToInclude = configuration.getSqlFragments().get(refid);
            return nodeToInclude.getNode().cloneNode(true); // 深拷贝
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
        NodeList children = node.getChildNodes(); // 获取 <include/> 下所有的子节点
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                String name = this.getStringAttribute(n, "name"); // 获取 name
                // 获取 value，并将其中的 ‘${}’ 占位符采用 inheritedVariablesContext 中的配置项进行替换
                String value = PropertyParser.parse(this.getStringAttribute(n, "value"), inheritedVariablesContext);
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
            // 将获取到的配置项与已有的配置项全部记录到新的 Properties 对象中返回
            Properties newProperties = new Properties();
            newProperties.putAll(inheritedVariablesContext);
            newProperties.putAll(declaredProperties);
            return newProperties;
        }
    }
}
