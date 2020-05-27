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

package org.apache.ibatis.parsing;

import org.apache.ibatis.builder.BuilderException;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XPathParser {

    private final Document document;
    private boolean validation;
    private EntityResolver entityResolver;
    private Properties variables;
    private XPath xpath;

    public XPathParser(String xml) {
        this.commonConstructor(false, null, null);
        this.document = this.createDocument(new InputSource(new StringReader(xml)));
    }

    public XPathParser(Reader reader) {
        this.commonConstructor(false, null, null);
        this.document = this.createDocument(new InputSource(reader));
    }

    public XPathParser(InputStream inputStream) {
        this.commonConstructor(false, null, null);
        this.document = this.createDocument(new InputSource(inputStream));
    }

    public XPathParser(Document document) {
        this.commonConstructor(false, null, null);
        this.document = document;
    }

    public XPathParser(String xml, boolean validation) {
        this.commonConstructor(validation, null, null);
        this.document = this.createDocument(new InputSource(new StringReader(xml)));
    }

    public XPathParser(Reader reader, boolean validation) {
        this.commonConstructor(validation, null, null);
        this.document = this.createDocument(new InputSource(reader));
    }

    public XPathParser(InputStream inputStream, boolean validation) {
        this.commonConstructor(validation, null, null);
        this.document = this.createDocument(new InputSource(inputStream));
    }

    public XPathParser(Document document, boolean validation) {
        this.commonConstructor(validation, null, null);
        this.document = document;
    }

    public XPathParser(String xml, boolean validation, Properties variables) {
        this.commonConstructor(validation, variables, null);
        this.document = this.createDocument(new InputSource(new StringReader(xml)));
    }

    public XPathParser(Reader reader, boolean validation, Properties variables) {
        this.commonConstructor(validation, variables, null);
        this.document = this.createDocument(new InputSource(reader));
    }

    public XPathParser(InputStream inputStream, boolean validation, Properties variables) {
        this.commonConstructor(validation, variables, null);
        this.document = this.createDocument(new InputSource(inputStream));
    }

    public XPathParser(Document document, boolean validation, Properties variables) {
        this.commonConstructor(validation, variables, null);
        this.document = document;
    }

    public XPathParser(String xml, boolean validation, Properties variables, EntityResolver entityResolver) {
        this.commonConstructor(validation, variables, entityResolver);
        this.document = this.createDocument(new InputSource(new StringReader(xml)));
    }

    public XPathParser(Reader reader, boolean validation, Properties variables, EntityResolver entityResolver) {
        this.commonConstructor(validation, variables, entityResolver);
        this.document = this.createDocument(new InputSource(reader));
    }

    public XPathParser(InputStream inputStream, boolean validation, Properties variables, EntityResolver entityResolver) {
        this.commonConstructor(validation, variables, entityResolver);
        this.document = this.createDocument(new InputSource(inputStream));
    }

    public XPathParser(Document document, boolean validation, Properties variables, EntityResolver entityResolver) {
        this.commonConstructor(validation, variables, entityResolver);
        this.document = document;
    }

    public void setVariables(Properties variables) {
        this.variables = variables;
    }

    public String evalString(String expression) {
        return this.evalString(document, expression);
    }

    public String evalString(Object root, String expression) {
        String result = (String) this.evaluate(expression, root, XPathConstants.STRING);
        result = PropertyParser.parse(result, variables);
        return result;
    }

    public Boolean evalBoolean(String expression) {
        return this.evalBoolean(document, expression);
    }

    public Boolean evalBoolean(Object root, String expression) {
        return (Boolean) this.evaluate(expression, root, XPathConstants.BOOLEAN);
    }

    public Short evalShort(String expression) {
        return this.evalShort(document, expression);
    }

    public Short evalShort(Object root, String expression) {
        return Short.valueOf(this.evalString(root, expression));
    }

    public Integer evalInteger(String expression) {
        return this.evalInteger(document, expression);
    }

    public Integer evalInteger(Object root, String expression) {
        return Integer.valueOf(this.evalString(root, expression));
    }

    public Long evalLong(String expression) {
        return this.evalLong(document, expression);
    }

    public Long evalLong(Object root, String expression) {
        return Long.valueOf(this.evalString(root, expression));
    }

    public Float evalFloat(String expression) {
        return this.evalFloat(document, expression);
    }

    public Float evalFloat(Object root, String expression) {
        return Float.valueOf(this.evalString(root, expression));
    }

    public Double evalDouble(String expression) {
        return this.evalDouble(document, expression);
    }

    public Double evalDouble(Object root, String expression) {
        return (Double) this.evaluate(expression, root, XPathConstants.NUMBER);
    }

    public List<XNode> evalNodes(String expression) {
        return this.evalNodes(document, expression);
    }

    public List<XNode> evalNodes(Object root, String expression) {
        List<XNode> xnodes = new ArrayList<>();
        NodeList nodes = (NodeList) this.evaluate(expression, root, XPathConstants.NODESET);
        for (int i = 0; i < nodes.getLength(); i++) {
            xnodes.add(new XNode(this, nodes.item(i), variables));
        }
        return xnodes;
    }

    public XNode evalNode(String expression) {
        return this.evalNode(document, expression);
    }

    public XNode evalNode(Object root, String expression) {
        Node node = (Node) this.evaluate(expression, root, XPathConstants.NODE);
        if (node == null) {
            return null;
        }
        return new XNode(this, node, variables);
    }

    private Object evaluate(String expression, Object root, QName returnType) {
        try {
            return xpath.evaluate(expression, root, returnType);
        } catch (Exception e) {
            throw new BuilderException("Error evaluating XPath.  Cause: " + e, e);
        }
    }

    private Document createDocument(InputSource inputSource) {
        // important: this must only be called AFTER common constructor
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setValidating(validation);

            factory.setNamespaceAware(false);
            factory.setIgnoringComments(true);
            factory.setIgnoringElementContentWhitespace(false);
            factory.setCoalescing(false);
            factory.setExpandEntityReferences(true);

            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver(entityResolver);
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                public void error(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void warning(SAXParseException exception) throws SAXException {
                    // NOP
                }
            });
            return builder.parse(inputSource);
        } catch (Exception e) {
            throw new BuilderException("Error creating document instance.  Cause: " + e, e);
        }
    }

    private void commonConstructor(boolean validation, Properties variables, EntityResolver entityResolver) {
        this.validation = validation;
        this.entityResolver = entityResolver;
        this.variables = variables;
        XPathFactory factory = XPathFactory.newInstance();
        this.xpath = factory.newXPath();
    }

}
