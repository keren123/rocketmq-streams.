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
package org.apache.rocketmq.streams.filter.builder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.rocketmq.streams.common.channel.sink.ISink;
import org.apache.rocketmq.streams.common.configurable.IConfigurable;
import org.apache.rocketmq.streams.common.configurable.IConfigurableService;
import org.apache.rocketmq.streams.common.datatype.DataType;
import org.apache.rocketmq.streams.common.metadata.MetaData;
import org.apache.rocketmq.streams.common.metadata.MetaDataField;
import org.apache.rocketmq.streams.common.model.NameCreator;
import org.apache.rocketmq.streams.common.utils.DataTypeUtil;
import org.apache.rocketmq.streams.common.utils.MapKeyUtil;
import org.apache.rocketmq.streams.db.driver.JDBCDriver;
import org.apache.rocketmq.streams.filter.contants.RuleElementType;
import org.apache.rocketmq.streams.filter.operator.Rule;
import org.apache.rocketmq.streams.filter.operator.action.Action;
import org.apache.rocketmq.streams.filter.operator.expression.Expression;
import org.apache.rocketmq.streams.filter.operator.expression.ExpressionRelationParser;
import org.apache.rocketmq.streams.filter.operator.expression.RelationExpression;
import org.apache.rocketmq.streams.filter.operator.var.ConstantVar;
import org.apache.rocketmq.streams.filter.operator.var.ContextVar;
import org.apache.rocketmq.streams.filter.operator.var.InnerVar;
import org.apache.rocketmq.streams.filter.operator.var.Var;

/**
 * ?????????????????????????????????????????????????????????????????????????????????????????????metadata????????????
 */
public class RuleBuilder {
    private static final DataType STRING = DataTypeUtil.getDataTypeFromClass(String.class);
    private Rule rule = new Rule();
    private List<Var> varList = new ArrayList<>();//??????????????????????????????????????????????????????
    private List<MetaData> metaDataList = new ArrayList<>();//???????????????metadata
    private List<Expression> expressionList = new ArrayList<>();//????????????????????????
    private List<Action> actionList = new ArrayList<>();//???????????????action
    private List<JDBCDriver> dataSourceList = new ArrayList<>();//???????????????datasource
    private MetaData metaData;//???????????????metadata
    private String namespace;//?????????????????????
    private String ruleName;//???????????????
    private Expression rootExpression;//????????????????????????
    private String ruleCode;
    private String ruleTitle;
    private String ruleDescription;
    private transient NameCreator actionNameCreator = new NameCreator();
    private transient NameCreator dataSourceNameCreator = new NameCreator();
    private transient NameCreator metaDataNameCreator = new NameCreator();

    /**
     * ?????????????????????creator????????????????????????????????????meta??????????????????????????????????????????????????????????????????????????????????????????????????????
     *
     * @param namespace     ?????????????????????
     * @param ruleName      ????????????
     * @param expressionStr ???????????????varname,functionName,datatype,value)&((varname,functionName,value)|(varname, functionName,value))
     * @param msgMetaInfo   ????????????????????????????????????????????????msgFieldName;int;true????????????????????????false??????????????????string?????????????????????????????? ???????????????msgFieldnName????????????????????????datatype.getDataTypeName??????
     */
    public RuleBuilder(String namespace, String ruleName, String expressionStr, String... msgMetaInfo) {
        init(namespace, ruleName);
        List<Expression> expressions = new ArrayList<>();
        List<RelationExpression> relationExpressions = new ArrayList<>();
        rule.setExpressionStr(expressionStr);
        Expression expression =
            ExpressionBuilder.createExpression(namespace, ruleName, expressionStr, expressions, relationExpressions);
        this.rootExpression = expression;
        expressionList.addAll(expressions);
        expressionList.addAll(relationExpressions);
        if (msgMetaInfo == null || msgMetaInfo.length == 0) {
            for (Expression express : expressionList) {
                Var var = RuleElementBuilder.createContextVar(namespace, ruleName, express.getVarName(),
                    null, express.getVarName());
                var.setConfigureName(express.getVarName());
                varList.add(var);
            }
            return;
        }
        MetaData metaData =
            RuleElementBuilder.createMetaData(namespace, metaDataNameCreator.createName(), msgMetaInfo);
        List<MetaDataField> metaDataFields = metaData.getMetaDataFields();
        Set<String> existVarNames = new HashSet<>();//
        for (MetaDataField metaDataField : metaDataFields) {
            Var var = RuleElementBuilder.createContextVar(namespace, ruleName, metaDataField.getFieldName(),
                metaData.getConfigureName(), metaDataField.getFieldName());
            var.setConfigureName(metaDataField.getFieldName());
            varList.add(var);
            existVarNames.add(var.getConfigureName());
        }
        for (Expression express : expressionList) {
            if (existVarNames.contains(express.getVarName())) {
                continue;
            }
            if (RelationExpression.class.isInstance(express)) {
                continue;
            }
            Var var = RuleElementBuilder.createContextVar(namespace, ruleName, express.getVarName(),
                null, express.getVarName());
            var.setConfigureName(express.getVarName());
            varList.add(var);
        }
        metaData.toObject(metaData.toJson());
        this.metaData = metaData;
        if (metaData != null) {
            this.rule.setMsgMetaDataName(metaData.getConfigureName());
        }
        metaDataList.add(this.metaData);
    }

    /**
     * ????????????
     *
     * @param namespace ?????????????????????
     * @param ruleName  ????????????
     */
    public RuleBuilder(String namespace, String ruleName) {
        init(namespace, ruleName);
        createChannelMetaData();
    }

    public Rule getRule() {
        return rule;
    }

    public RuleBuilder(IConfigurableService ruleEngineConfigurableService, String namespace, String ruleName) {
        Rule rule =
            (Rule)ruleEngineConfigurableService.queryConfigurableByIdent(RuleElementType.RULE.getType(), ruleName);
        if (rule == null) {
            init(namespace, ruleName);
            createChannelMetaData();
            return;
        }
        this.metaData =
            (MetaData)ruleEngineConfigurableService.queryConfigurableByIdent(RuleElementType.METADATA.getType(),
                rule.getMsgMetaDataName());
        this.rootExpression =
            (Expression)ruleEngineConfigurableService.queryConfigurableByIdent(RuleElementType.EXPRESSION.getType(),
                rule.getExpressionName());
        this.rule = rule;
        this.ruleName = rule.getConfigureName();
        this.namespace = rule.getNameSpace();
        this.ruleTitle = rule.getRuleTitle();
        this.ruleCode = rule.getRuleCode();
        this.ruleDescription = rule.getRuleDesc();
        this.varList = ruleEngineConfigurableService.queryConfigurableByType(RuleElementType.VAR.getType());
        this.expressionList =
            ruleEngineConfigurableService.queryConfigurableByType(RuleElementType.EXPRESSION.getType());
        this.metaDataList = ruleEngineConfigurableService.queryConfigurableByType(RuleElementType.METADATA.getType());
        this.actionList = ruleEngineConfigurableService.queryConfigurableByType(RuleElementType.ACTION.getType());
        this.dataSourceList =
            ruleEngineConfigurableService.queryConfigurableByType(RuleElementType.DATASOURCE.getType());
    }

    /**
     * ??????????????????????????????
     *
     * @param namespace
     * @param ruleName
     * @param rulecode_title_description ??????3???????????????rulecode???ruletitle???ruledescription
     */
    private void init(String namespace, String ruleName, String... rulecode_title_description) {
        rule.setNameSpace(namespace);
        rule.setConfigureName(ruleName);
        rule.setRuleStatus(3);
        this.namespace = namespace;
        this.ruleName = ruleName;

        if (rulecode_title_description != null && rulecode_title_description.length > 0) {
            rule.setRuleCode(rulecode_title_description[0]);
            this.ruleCode = rule.getRuleCode();
            if (rulecode_title_description.length > 1) {
                rule.setRuleTitle(rulecode_title_description[1]);
                this.ruleTitle = rule.getRuleTitle();
            }
            if (rulecode_title_description.length > 2) {
                rule.setRuleDesc(rulecode_title_description[2]);
                ruleDescription = rule.getRuleDesc();
            }
        }
    }

    /**
     * ????????????????????????meta????????????????????????metafield
     *
     * @param fieldNameTypeIsRequireds ???????????????msgkeyName:type;isRequired??? ?????????????????????false??????????????????string?????????????????????????????? ???????????????msgFieldnName????????????????????????datatype.getDataTypeName??????
     */
    public RuleBuilder addChannelMetaDataField(String... fieldNameTypeIsRequireds) {
        if (metaData == null) {
            throw new RuntimeException("need create metaData first");
        }
        if (metaData.getMetaDataFields() == null) {
            metaData.setMetaDataFields(new ArrayList<MetaDataField>());
        }
        this.metaData.getMetaDataFields().addAll(createMetaDataField(fieldNameTypeIsRequireds));
        return this;
    }

    /**
     * ????????????????????????meta????????????????????????metafield
     *
     * @param fieldNameTypeIsRequireds ???????????????msgkeyName:type;isRequired??? ?????????????????????false??????????????????string?????????????????????????????? ???????????????msgFieldnName????????????????????????datatype.getDataTypeName??????
     */
    private List<MetaDataField> createMetaDataField(String... fieldNameTypeIsRequireds) {
        MetaData metaData = RuleElementBuilder.createMetaData("tmp", "tmp", fieldNameTypeIsRequireds);
        return metaData.getMetaDataFields();
    }

    /**
     * ???????????????????????????metadatafield???????????????????????????
     *
     * @param fieldNameTypeIsRequireds
     */
    public RuleBuilder addVarAndMetaDataField(String... fieldNameTypeIsRequireds) {
        List<MetaDataField> metaDataFields = createMetaDataField(fieldNameTypeIsRequireds);
        this.metaData.getMetaDataFields().addAll(metaDataFields);
        for (MetaDataField metaDataField : metaDataFields) {
            ContextVar var = RuleElementBuilder.createContextVar(namespace, ruleName, metaDataField.getFieldName(),
                metaData.getConfigureName(), metaDataField.getFieldName());
            varList.add(var);
        }
        return this;
    }

    public RuleBuilder addInnerVar(String varName) {
        InnerVar innerVar = new InnerVar();
        innerVar.setNameSpace(ruleName);
        innerVar.setVarName(varName);
        innerVar.setType(RuleElementType.VAR.getType());
        varList.add(innerVar);
        return this;
    }

    /**
     * ??????????????????????????????????????????
     *
     * @param varName
     * @param value
     * @return
     */
    public RuleBuilder addConstantAndMetaField(String varName, String value) {
        return addConstantAndMetaField(varName, STRING, value);
    }

    /**
     * ?????????????????????????????????????????????
     *
     * @param varName
     * @param value
     * @return
     */
    public RuleBuilder addConstantAndMetaField(String varName, DataType dataType, String value) {
        ConstantVar var = RuleElementBuilder.createConstantVar(namespace, ruleName, varName, dataType, value);
        varList.add(var);
        MetaDataField metaDataField = new MetaDataField();
        metaDataField.setFieldName(varName);
        metaDataField.setDataType(dataType);
        metaDataField.setIsRequired(false);
        this.metaData.getMetaDataFields().add(metaDataField);
        return this;
    }

    public RuleBuilder setExpression(String expressionStr) {
        List<Expression> expressions = new ArrayList<>();
        List<RelationExpression> relationExpressions = new ArrayList<>();
        Expression expression =
            ExpressionBuilder.createExpression(namespace, ruleName, expressionStr, expressions, relationExpressions);
        this.rootExpression = expression;
        this.expressionList.addAll(expressions);
        this.expressionList.addAll(relationExpressions);
        return this;
    }

    /**
     * ???????????????????????????????????????????????????????????????????????????????????????
     *
     * @param expressionName
     * @param varName
     * @param functionName
     * @param value
     * @return
     */
    public RuleBuilder addExpression(String expressionName, String varName, String functionName, String value) {
        addExpression(expressionName, varName, functionName, STRING, value);
        return this;
    }

    public RuleBuilder addExpression(Expression expression) {
        expressionList.add(expression);
        return this;
    }

    /**
     * ????????????????????????????????????????????????????????????????????????????????????
     *
     * @param expressionName
     * @param varName
     * @param functionName
     * @param value
     * @return
     */
    public RuleBuilder addExpression(String expressionName, String varName, String functionName, DataType dataType,
                                     String value) {
        Expression expression =
            RuleElementBuilder.createExpression(ruleName, expressionName, varName, functionName, dataType, value);
        expressionList.add(expression);
        return this;
    }

    /**
     * ?????? ???????????????????????????????????????????????????????????????1???2???3?????????????????????1&(2???3)
     *
     * @param relationStr
     * @return
     */
    public RuleBuilder addRelationExpression(String relationStr) {
        List<RelationExpression> relationExpressions = new ArrayList<>();
        RelationExpression relationExpression =
            ExpressionRelationParser.createRelations(ruleName, ruleName, relationStr, relationExpressions);
        this.expressionList.addAll(relationExpressions);
        this.rootExpression = relationExpression;
        return this;
    }
    //
    ///**
    // * @param url
    // * @param userName
    // * @param password
    // * @param kvs:     varName:fieldName,varName:fieldName
    // * @return
    // */
    //public RuleBuilder addDBAction(String url, String userName, String password, String table, String... kvs) {
    //    return addDBAction(url, userName, password, table, createVarName2FieldName(kvs));
    //}
    //
    //public RuleBuilder addChannelAction(IConvertDataSource convertDataSource) {
    //    Map<String, String> varName2FieldName = new HashMap<>();
    //    List<MetaDataField> metaDataFields = metaData.getMetaDataFields();
    //    for (MetaDataField field : metaDataFields) {
    //        varName2FieldName.put(field.getFieldName(), field.getFieldName());
    //    }
    //    IDataOperator dataSource = convertDataSource.convert();
    //    dataSourceList.add(dataSource);
    //    String metaDataName = metaDataNameCreator.createName();
    //    String actionName = actionNameCreator.createName();
    //    MetaData metaData =
    //        RuleElementBuilder.createMetaData(namespace, metaDataName, createMetaDataFiledStr(varName2FieldName));
    //    metaData.setDataSourceName(dataSource.getConfigureName());
    //    metaDataList.add(metaData);
    //    MetaDataAction action =
    //        RuleElementBuilder.createAction(namespace, actionName, metaDataName, varName2FieldName);
    //    actionList.add(action);
    //    return this;
    //}







    /**
     * ???????????????????????????
     *
     * @param ruleEngineConfigurableService
     * @return
     */
    public Rule generateRule(IConfigurableService ruleEngineConfigurableService) {
        Rule rule = createRule();
        insertOrUpdate(ruleEngineConfigurableService);
        rule.initElements();
        return rule;
    }

    public Rule generateRule() {
        return generateRule(null);
    }

    private void insertOrUpdate(IConfigurableService ruleEngineConfigurableService) {
        insertOrUpdate(ruleEngineConfigurableService, metaDataList, MetaData.TYPE);
        insertOrUpdate(ruleEngineConfigurableService, varList, Var.TYPE);
        insertOrUpdate(ruleEngineConfigurableService, expressionList, Expression.TYPE);
        insertOrUpdate(ruleEngineConfigurableService, actionList, Action.TYPE);
        insertOrUpdate(ruleEngineConfigurableService, dataSourceList, ISink.TYPE);
        if (ruleEngineConfigurableService != null) {
            ruleEngineConfigurableService.insert(rule);
        }
    }

    /**
     * ??????????????????,?????????????????????
     *
     * @return
     */
    protected Rule createRule() {
        if (rootExpression == null && expressionList != null && expressionList.size() > 0) {
            rootExpression = expressionList.get(0);
        }
        rule.setExpressionName(rootExpression.getConfigureName());
        rule.setActionNames(convert(actionList));
        rule.setVarNames(convert(varList));
        rule.setRuleCode(ruleCode);
        rule.setRuleTitle(ruleTitle);
        rule.setRuleDesc(ruleDescription);
        if (this.metaData != null) {
            rule.setMsgMetaDataName(metaData.getConfigureName());
        }
        return rule;
    }

    private Map<String, String> createVarName2FieldName(String... kvs) {
        Map<String, String> varName2FieldNames = null;
        if (kvs != null) {
            varName2FieldNames = new HashMap<>();
            for (String varName2FieldName : kvs) {
                String[] values = varName2FieldName.split(":");
                varName2FieldNames.put(values[0], values[1]);
            }
            return varName2FieldNames;
        }
        initVarName2FieldName(varName2FieldNames);
        return varName2FieldNames;
    }

    private void initVarName2FieldName(Map<String, String> varName2FieldNames) {
        if (varName2FieldNames == null) {
            List<MetaDataField> metaDataFields = metaData.getMetaDataFields();
            for (MetaDataField field : metaDataFields) {
                varName2FieldNames.put(field.getFieldName(), field.getFieldName());
            }
        }
    }

    private String[] createMetaDataFiledStr(Map<String, String> varName2FieldName) {
        if (varName2FieldName == null) {
            return new String[0];
        }
        String[] filedStr = new String[varName2FieldName.size()];
        Iterator<Map.Entry<String, String>> it = varName2FieldName.entrySet().iterator();
        metaData.toObject(metaData.toJson());
        int i = 0;
        while (it.hasNext()) {
            Map.Entry<String, String> entry = it.next();
            String varName = entry.getKey();
            String fieldName = entry.getValue();
            DataType dataType = metaData.getMetaDataField(varName).getDataType();
            boolean isRequired = metaData.getMetaDataField(varName).getIsRequired();
            filedStr[i] = MapKeyUtil.createKey(fieldName, dataType.getDataTypeName(), String.valueOf(isRequired));
            i++;
        }
        return filedStr;
    }

    private <T extends IConfigurable> void insertOrUpdate(IConfigurableService ruleEngineConfigurableService,
                                                          List<T> configurables, String type) {
        if (configurables == null) {
            return;
        }
        for (IConfigurable configurable : configurables) {
            if (ruleEngineConfigurableService != null) {
                ruleEngineConfigurableService.insert(configurable);
            }
            rule.putConfigurableMap(configurable, type);
        }
    }

    private <T extends IConfigurable> List<String> convert(List<T> configurables) {
        List<String> configurableNames = new ArrayList<>();
        for (T t : configurables) {
            configurableNames.add(t.getConfigureName());
        }
        return configurableNames;
    }

    /**
     * ????????????????????????????????????????????????metadata
     *
     * @return
     */
    private MetaData createChannelMetaData() {
        MetaData metaData = RuleElementBuilder.createMetaData(namespace, metaDataNameCreator.createName());
        metaDataList.add(metaData);
        this.metaData = metaData;
        return metaData;
    }

    public MetaData getMetaData() {
        return metaData;
    }

    public List<Var> getVarList() {
        return varList;
    }

    public List<MetaData> getMetaDataList() {
        return metaDataList;
    }

    public List<Expression> getExpressionList() {
        return expressionList;
    }

    public List<Action> getActionList() {
        return actionList;
    }

    public List<JDBCDriver> getDataSourceList() {
        return dataSourceList;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getRuleName() {
        return ruleName;
    }

    public Expression getRootExpression() {
        return rootExpression;
    }

    public void setRootExpression(Expression rootExpression) {
        this.rootExpression = rootExpression;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public void setRuleCode(String ruleCode) {
        this.ruleCode = ruleCode;
    }

    public String getRuleTitle() {
        return ruleTitle;
    }

    public void setRuleTitle(String ruleTitle) {
        this.ruleTitle = ruleTitle;
    }
}
