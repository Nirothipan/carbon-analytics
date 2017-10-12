/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.business.rules.core.services;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.business.rules.core.bean.Artifact;
import org.wso2.carbon.business.rules.core.bean.BusinessRule;
import org.wso2.carbon.business.rules.core.bean.RuleTemplate;
import org.wso2.carbon.business.rules.core.bean.Template;
import org.wso2.carbon.business.rules.core.bean.TemplateGroup;
import org.wso2.carbon.business.rules.core.bean.scratch.BusinessRuleFromScratch;
import org.wso2.carbon.business.rules.core.bean.scratch.BusinessRuleFromScratchProperty;
import org.wso2.carbon.business.rules.core.bean.template.BusinessRuleFromTemplate;
import org.wso2.carbon.business.rules.core.datasource.QueryExecutor;
import org.wso2.carbon.business.rules.core.deployer.SiddhiAppApiHelper;
import org.wso2.carbon.business.rules.core.exceptions.BusinessRulesDatasourceException;
import org.wso2.carbon.business.rules.core.exceptions.TemplateManagerException;
import org.wso2.carbon.business.rules.core.services.template.BusinessRulesFromTemplate;
import org.wso2.carbon.business.rules.core.util.TemplateManagerConstants;
import org.wso2.carbon.business.rules.core.util.TemplateManagerHelper;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.sql.Blob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * The exposed Template Manager service, which contains methods related to
 * Business Rules from template, and Business Rules from scratch
 */
public class TemplateManagerService implements BusinessRulesService {
    private static final Logger log = LoggerFactory.getLogger(TemplateManagerService.class);
    // Available Template Groups from the directory
    private Map<String, TemplateGroup> availableTemplateGroups;
    private Map<String, BusinessRule> availableBusinessRules;

    public TemplateManagerService() {
        // Load & store available Template Groups & Business Rules at the time of instantiation
        this.availableTemplateGroups = loadTemplateGroups();
        this.availableBusinessRules = loadBusinessRules();
    }

    public void createBusinessRuleFromTemplate(BusinessRuleFromTemplate businessRuleFromTemplate) {
        try {
            // To store derived artifacts from the templates specified in the given business rule
            Map<String, Artifact> derivedArtifacts = null;
            // To maintain deployment status of all the artifacts
            boolean isDeployed;
            try {
                derivedArtifacts = deriveArtifacts(businessRuleFromTemplate);
            } catch (TemplateManagerException e) {
                log.error("Error in deriving templates", e);
            }
            String businessRuleUUID = businessRuleFromTemplate.getUuid();
            try {
                // Business Rule deployment status
                isDeployed = deployBusinessRule(derivedArtifacts, businessRuleFromTemplate);
                // Saving Deployed BusinessRule
                saveBusinessRuleDefinition(businessRuleUUID, businessRuleFromTemplate, isDeployed);
            } catch (TemplateManagerException e) {
                // Saving definition is unsuccessful
                log.error("Error in saving the Business Rule definition", e);
                // Exception is thrown from the saveBusinessRuleDefinition method itself
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    public void createBusinessRuleFromScratch(BusinessRuleFromScratch businessRuleFromScratch) {
        try {

            // To store derived artifacts from the templates specified in the given business rule
            Map<String, Artifact> derivedArtifacts = null;
            // To maintain deployment status of all the artifacts
            boolean isDeployed;
            Artifact deployableSiddhiApp = null;
            try {
                // Derive input & output siddhiApp artifacts
                derivedArtifacts = deriveArtifacts(businessRuleFromScratch);
                // This siddhiApp will be deployed finally
                deployableSiddhiApp = buildSiddhiAppFromScratch(derivedArtifacts, businessRuleFromScratch);
            } catch (TemplateManagerException e) {
                log.error("Error in deriving templates", e);
            }
            String businessRuleUUID = businessRuleFromScratch.getUuid();

            try {
                isDeployed = deployBusinessRule(deployableSiddhiApp, businessRuleFromScratch);
                saveBusinessRuleDefinition(businessRuleUUID, businessRuleFromScratch, isDeployed);
            } catch (TemplateManagerException e) {
                // Saving definition is unsuccessful
                log.error("Error in saving the Business Rule definition", e);
                // Exception is thrown from the saveBusinessRuleDefinition method itself            }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    public void editBusinessRuleFromTemplate(String uuid, BusinessRuleFromTemplate businessRuleFromTemplate) {
        // todo: verify next lower level
        Map<String, Artifact> derivedArtifacts = null;
        boolean isDeployed = false;
        try {
            derivedArtifacts = deriveArtifacts(businessRuleFromTemplate);
        } catch (TemplateManagerException e) {
            log.error(e.getMessage(), e);
        }

        try {
            // Load all available Business Rules again
            this.availableBusinessRules = loadBusinessRules();
            // Update Deploy templates, only if overwriting Business Rule Definition is successful
            // todo: (Q) is this ok?
            for (String artifactUUID : derivedArtifacts.keySet()) {
                updateDeployedArtifact(artifactUUID, derivedArtifacts.get(artifactUUID));
            }
            overwriteBusinessRuleDefinition(uuid, businessRuleFromTemplate, isDeployed);

        } catch (TemplateManagerException | UnsupportedEncodingException | SQLException |
                BusinessRulesDatasourceException e) {
            // Overwriting definition / Update Deploy unsuccessful
            log.error(e.getMessage(), e);
        }
    }

    public void editBusinessRuleFromScratch(String uuid, BusinessRuleFromScratch businessRuleFromScratch) {
        Map<String, Artifact> derivedArtifacts = null;
        boolean isDeployed = false;
        Artifact deployableSiddhiApp = null;
        try {
            derivedArtifacts = deriveArtifacts(businessRuleFromScratch);

            deployableSiddhiApp = buildSiddhiAppFromScratch(derivedArtifacts, businessRuleFromScratch);
        } catch (TemplateManagerException e) {
            log.error(e.getMessage(), e);
        }

        try {
            isDeployed = updateDeployedArtifact(businessRuleFromScratch.getUuid(), deployableSiddhiApp);
            overwriteBusinessRuleDefinition(uuid, businessRuleFromScratch, isDeployed);

            this.availableBusinessRules = loadBusinessRules();
        } catch (TemplateManagerException | BusinessRulesDatasourceException | UnsupportedEncodingException |
                SQLException e) {
            log.error(e.getMessage(), e);
        }
    }

    public BusinessRule findBusinessRule(String businessRuleUUID) throws TemplateManagerException {
        for (String availableBusinessRuleUUID : availableBusinessRules.keySet()) {
            if (availableBusinessRuleUUID.equals(businessRuleUUID)) {
                return availableBusinessRules.get(availableBusinessRuleUUID);
            }
        }

        throw new TemplateManagerException("No Business Rule found with the UUID : " + businessRuleUUID);
    }

    public void deleteBusinessRule(String uuid) throws TemplateManagerException { // todo: verify next lower level
        BusinessRule foundBusinessRule;
        try {
            foundBusinessRule = findBusinessRule(uuid);
        } catch (TemplateManagerException e) {
            // No Business Rule Found
            log.error("No business rule found", e);
            // No point of further execution
            return;
        }

        // If found Business Rule is from Template
        if (foundBusinessRule instanceof BusinessRulesFromTemplate) {
            BusinessRuleFromTemplate foundBusinessRuleFromTemplate = (BusinessRuleFromTemplate) foundBusinessRule;
            Collection<Template> templates = getTemplates(foundBusinessRuleFromTemplate);
            Boolean isSuccessfullyUndeployed = true;
            for (int i = 0; i < templates.size(); i++) {
                try {
                    undeploySiddhiApp(foundBusinessRuleFromTemplate.getUuid() + "_" + i);
                } catch (TemplateManagerException e) {
                    isSuccessfullyUndeployed = false;
                }
            }
            // If all Templates are undeployed
            if (isSuccessfullyUndeployed) {
                try {
                    removeBusinessRuleDefinition(uuid);
                } catch (TemplateManagerException e) {
                    log.error("Failed to delete Business Rule definition of : " + uuid, e);
                } catch (SQLException | BusinessRulesDatasourceException e) {
                    log.error(e.getMessage(), e);
                }
            } else {
                log.error("Failed to un-deploy all the templates. Unable to delete the Business Rule definition of : "
                        + uuid); // todo: (Q) is this ok?
            }
        }
        if (foundBusinessRule instanceof BusinessRuleFromScratch) {
            BusinessRuleFromScratch foundBusinessRuleFromScratch = (BusinessRuleFromScratch) foundBusinessRule;
            boolean isCompletelyUndeployed = true;
            try {
                undeploySiddhiApp(foundBusinessRuleFromScratch.getUuid());
            } catch (TemplateManagerException e) {
                isCompletelyUndeployed = false;
                log.error("Failed to un-deploy " + foundBusinessRuleFromScratch.getUuid());
            }

            if (isCompletelyUndeployed) {
                try {
                    removeBusinessRuleDefinition(foundBusinessRuleFromScratch.getUuid());
                } catch (TemplateManagerException e) {
                    log.error("Failed to delete business rule definition of : " + uuid, e);
                } catch (BusinessRulesDatasourceException | SQLException e) {
                    log.error(e.getMessage(), e);
                }
            }
        }
        // todo: else: If found Business Rule is from scratch
    }

    public boolean deployBusinessRule(Map<String, Artifact> derivedArtifacts, BusinessRuleFromTemplate
            businessRuleFromTemplate)
            throws TemplateManagerException {
        for (String templateUUID : derivedArtifacts.keySet()) {
            try {
                deployTemplate(templateUUID, derivedArtifacts.get(templateUUID));
            } catch (TemplateManagerException e) {
                log.error("Failed to deploy " + derivedArtifacts.get(templateUUID).getType() + " : " + templateUUID, e);
                return false;
            }

        }
        return true;
    }

    public boolean deployBusinessRule(Artifact deployableSiddhiApp, BusinessRuleFromScratch businessRuleFromScratch)
            throws
            TemplateManagerException {
        try {

            deploySiddhiApp(businessRuleFromScratch.getUuid(), deployableSiddhiApp);
        } catch (TemplateManagerException e) {
            log.error("Failed to deploy businessRule:" + businessRuleFromScratch + " ", e);
            return false;
        }
        return true;

    }

    public boolean updateBusinessRule(BusinessRuleFromScratch businessRuleFromScratch) throws TemplateManagerException {
        Map<String, Artifact> derivedTemplates = deriveArtifacts(businessRuleFromScratch);
        Artifact deployableSiddhiApp = buildSiddhiAppFromScratch(derivedTemplates, businessRuleFromScratch);
        boolean isDeployed;
        try {
            isDeployed = updateDeployedSiddhiApp(businessRuleFromScratch.getUuid(), deployableSiddhiApp);
        } catch (TemplateManagerException e) {
            log.error("Failed to update businessRule " + businessRuleFromScratch.getUuid() + ": ", e);
            return false;
        }
        return isDeployed;
    }

    public void deployTemplates(BusinessRuleFromTemplate businessRuleFromTemplate) throws TemplateManagerException {
        Map<String, Artifact> derivedTemplates = deriveArtifacts(businessRuleFromTemplate);
        for (String templateUUID : derivedTemplates.keySet()) {
            try {
                deployTemplate(templateUUID, derivedTemplates.get(templateUUID));
            } catch (TemplateManagerException e) {
                log.error("Failed to deploy " + derivedTemplates.get(templateUUID).getType() + " : " + templateUUID, e);
            }
        }
    }

    /**
     * Loads and returns available Template Groups from the directory
     *
     * @return
     */
    public Map<String, TemplateGroup> loadTemplateGroups() {

        File directory = new File(TemplateManagerConstants.TEMPLATES_DIRECTORY);
        // To store UUID and Template Group object
        Map<String, TemplateGroup> templateGroups = new HashMap();

        // Files from the directory
        File[] files = directory.listFiles();
        if (files != null) {
            for (final File fileEntry : files) {
                // If file is a valid json file
                if (fileEntry.isFile() && fileEntry.getName().endsWith("json")) {
                    // To store the converted file as an object
                    TemplateGroup templateGroup = null;

                    // convert and store
                    try {
                        templateGroup = TemplateManagerHelper.jsonToTemplateGroup(TemplateManagerHelper
                                .fileToJson(fileEntry));
                    } catch (TemplateManagerException e) {
                        log.error("Error in converting the file " + fileEntry.getName(), e);
                    }

                    // If file to object conversion is successful
                    if (templateGroup != null) {
                        try {
                            TemplateManagerHelper.validateTemplateGroup(templateGroup);
                            // Put to map, as denotable by UUID
                            templateGroups.put(templateGroup.getUuid(), templateGroup);
                        } catch (TemplateManagerException e) {
                            // Invalid Template Configuration file is found
                            // Abort loading the current file and continue with the next file
                            log.error("Invalid Template Group configuration file found: " + fileEntry.getName(), e);
                        }
                    } else {
                        log.error("Error in converting the file " + fileEntry.getName());
                    }

                }
            }
        }

        return templateGroups;
    }

    /**
     * Loads and returns available Business Rules from the database
     *
     * @return
     */
    public Map<String, BusinessRule> loadBusinessRules() {
        QueryExecutor queryExecutor = new QueryExecutor();
        Map<String, BusinessRule> map = new HashMap<>();

            int count = 0;
        try {
            ResultSet resultSet = queryExecutor.executeRetrieveAllBusinessRules();

            while (resultSet.next()) {
                String businessRuleUUID = resultSet.getString(1);
                Blob blob = resultSet.getBlob(2);
                byte[] bdata = blob.getBytes(1, (int) blob.length());

                JsonObject jsonObject = null;

                jsonObject = new Gson().fromJson(new String(bdata), JsonObject.class).getAsJsonObject();

                String uuid = jsonObject.get("uuid").getAsString();
                String name = jsonObject.get("name").getAsString();
                String templateGroupUUID = jsonObject.get("templateGroupUUID").getAsString();
                String type = jsonObject.get("type").getAsString();

                if ("scratch".equalsIgnoreCase(type)) {
                    String inputRuleTemplateUUID = jsonObject.get("inputRuleTemplateUUID").getAsString();
                    String outputRuleTemplateUUID = jsonObject.get("outputRuleTemplateUUID").getAsString();
                    BusinessRuleFromScratchProperty properties = new Gson().fromJson(jsonObject.get("properties"), BusinessRuleFromScratchProperty.class);
                    BusinessRule businessRule = new BusinessRuleFromScratch(uuid, name, templateGroupUUID, type, inputRuleTemplateUUID, outputRuleTemplateUUID, properties);
                    map.put(businessRuleUUID, businessRule);
                } else if ("template".equalsIgnoreCase(type)) {
                    String ruleTemplateUUID = jsonObject.get("ruleTemplateUUID").getAsString();
                    Map<String, String> properties = new Gson().fromJson(jsonObject.get("properties"), HashMap.class);
                    BusinessRule businessRule = new BusinessRuleFromTemplate(uuid, name, templateGroupUUID, type,
                            ruleTemplateUUID, properties);
                    map.put(businessRuleUUID, businessRule);
                }
        }
        } catch (SQLException e) {
            e.printStackTrace();
        } catch (BusinessRulesDatasourceException e) {
            log.error(e.getMessage()); // TODO : refine error messages
        }
        return map;
    }

    /**
     * Returns available Template Group objects, denoted by UUIDs
     *
     * @return
     */
    public Map<String, TemplateGroup> getTemplateGroups() {
        return this.availableTemplateGroups;
    }

    /**
     * Gets the Template Group, that has the given UUID
     *
     * @param templateGroupUUID
     * @return
     */
    public TemplateGroup getTemplateGroup(String templateGroupUUID) throws TemplateManagerException {
        for (String availableTemplateGroupUUID : availableTemplateGroups.keySet()) {
            if (availableTemplateGroupUUID.equals(templateGroupUUID)) {
                return availableTemplateGroups.get(availableTemplateGroupUUID);
            }
        }
        throw new TemplateManagerException("No template group found with the UUID - " + templateGroupUUID);
    }

    /**
     * Returns RuleTemplate objects belonging to the given Template Group, denoted by UUIDs
     *
     * @param templateGroupUUID
     * @return
     */
    public Map<String, RuleTemplate> getRuleTemplates(String templateGroupUUID) throws TemplateManagerException {
        HashMap<String, RuleTemplate> ruleTemplates = new HashMap<String, RuleTemplate>();
        for (String availableTemplateGroupUUID : availableTemplateGroups.keySet()) {
            // If matching UUID found
            if (availableTemplateGroupUUID.equals(templateGroupUUID)) {
                TemplateGroup foundTemplateGroup = availableTemplateGroups.get(availableTemplateGroupUUID);
                Collection<RuleTemplate> foundRuleTemplates = foundTemplateGroup.getRuleTemplates();

                // Put all the found Rule Templates denoted by their UUIDs, for returning
                for (RuleTemplate foundRuleTemplate : foundRuleTemplates) {
                    ruleTemplates.put(foundRuleTemplate.getName(), foundRuleTemplate);
                }

                return ruleTemplates;
            }
        }

        throw new TemplateManagerException("No template group found with the UUID - " + templateGroupUUID);
    }

    /**
     * Gets Rule Template, which belongs to the given Template Group and has the given Rule Template UUID
     *
     * @param templateGroupUUID
     * @param ruleTemplateUUID
     * @return
     */
    public RuleTemplate getRuleTemplate(String templateGroupUUID, String ruleTemplateUUID)
            throws TemplateManagerException {
        TemplateGroup foundTemplateGroup = getTemplateGroup(templateGroupUUID);
        for (RuleTemplate ruleTemplate : foundTemplateGroup.getRuleTemplates()) {
            if (ruleTemplate.getUuid().equals(ruleTemplateUUID)) {
                return ruleTemplate;
            }
        }

        throw new TemplateManagerException("No rule template found with the UUID - " + ruleTemplateUUID);
    }

    /**
     * Returns available Business Rule objects, denoted by UUIDs
     *
     * @return
     */
    public Map<String, BusinessRule> getBusinessRules() {
        return this.availableBusinessRules;
    }

    /**
     * Derives Artifacts from Templates in the given BusinessRuleFromTemplate.
     * - RuleTemplate is found, and its templated properties are replaced with the values
     * directly specified in the properties map,
     * and the values generated from the script - referring to the specified properties
     *
     * @param businessRuleFromTemplate
     * @return Templates with replaced properties in the content, denoted by their UUIDs
     */
    public Map<String, Artifact> deriveArtifacts(BusinessRuleFromTemplate businessRuleFromTemplate)
            throws TemplateManagerException {
        // To contain given replacement values, and values generated from the script
        Map<String, String> replacementValues = businessRuleFromTemplate.getProperties();
        // To store derived Artifact types and Artifacts
        HashMap<String, Artifact> derivedArtifacts = new HashMap<String, Artifact>();

        // Find the RuleTemplate specified in the BusinessRule
        RuleTemplate foundRuleTemplate = getRuleTemplate(businessRuleFromTemplate.getTemplateGroupUUID(),
                businessRuleFromTemplate.getRuleTemplateUUID());
        // Get script with templated elements and replace with values given in the BusinessRule
        String scriptWithTemplatedElements = foundRuleTemplate.getScript();
        String runnableScript = TemplateManagerHelper.replaceRegex(scriptWithTemplatedElements,
                TemplateManagerConstants.TEMPLATED_ELEMENT_NAME_REGEX_PATTERN,
                businessRuleFromTemplate.getProperties());

        // Run the script to get all the contained variables
        Map<String, String> scriptGeneratedVariables = TemplateManagerHelper.
                getScriptGeneratedVariables(runnableScript);

        // Get available Templates under the Rule Template, which is specified in the Business Rule
        Collection<Template> templatesToBeUsed = getTemplates(businessRuleFromTemplate);
        // Get properties to map and replace - as specified in the Business Rule, plus variables from the script
        Map<String, String> propertiesToMap = businessRuleFromTemplate.getProperties();
        propertiesToMap.putAll(scriptGeneratedVariables);
        int i = 0;
        // For each template to be used for the Business Rule
        for (Template template : templatesToBeUsed) {
            // If Template is a SiddhiApp
            if (template.getType().equals(TemplateManagerConstants.TEMPLATE_TYPE_SIDDHI_APP)) {
                // Derive SiddhiApp with the map containing properties for replacement
                Artifact derivedSiddhiApp = deriveSiddhiApp(template, propertiesToMap);
                try {
                    derivedSiddhiApp.setContent(derivedSiddhiApp.getContent().replaceAll(TemplateManagerConstants
                                    .SIDDHI_APP_NAME_REGEX_PATTERN,
                            "@App:name('" + businessRuleFromTemplate.getUuid() + "_" + i + "') "));
                    derivedArtifacts.put(TemplateManagerHelper.getSiddhiAppName(derivedSiddhiApp), derivedSiddhiApp);
                    i++;
                } catch (TemplateManagerException e) {
                    log.error("Error in deriving SiddhiApp", e);
                }
            }
            // Other template types are not concerned for now
        }

        return derivedArtifacts;
    }

    /**
     * Derives input and output siddhi apps, that would be combined to create the final SiddhiApp artifact
     *
     * @param businessRuleFromScratch
     * @return
     * @throws TemplateManagerException
     */
    public Map<String, Artifact> deriveArtifacts(BusinessRuleFromScratch businessRuleFromScratch) throws
            TemplateManagerException {
        // Get values to replace, from the Business Rule definition
        BusinessRuleFromScratchProperty replacementValues = businessRuleFromScratch.getProperties();

        HashMap<String, Artifact> derivedArtifacts = new HashMap<>();

        // Get input & output Rule Templates
        RuleTemplate inputRuleTemplate = getRuleTemplate(businessRuleFromScratch.getTemplateGroupUUID(),
                businessRuleFromScratch.getInputRuleTemplateUUID());
        RuleTemplate outputRuleTemplate = getRuleTemplate(businessRuleFromScratch.getTemplateGroupUUID(),
                businessRuleFromScratch.getOutputRuleTemplateUUID());

        // Get scripts of input & output Rule Templates
        String inputRuleTemplateScript = inputRuleTemplate.getScript();
        String outputRuleTemplateScript = outputRuleTemplate.getScript();

        // Get runnable scripts of input & output Rule Templates
        String runnableInputScript = TemplateManagerHelper.replaceRegex(inputRuleTemplateScript,
                TemplateManagerConstants.TEMPLATED_ELEMENT_NAME_REGEX_PATTERN, businessRuleFromScratch.getProperties()
                        .getInputData());
        String runnableOutputScript = TemplateManagerHelper.replaceRegex(outputRuleTemplateScript,
                TemplateManagerConstants.TEMPLATED_ELEMENT_NAME_REGEX_PATTERN, businessRuleFromScratch.getProperties()
                        .getOutputData());

        // Get variables generated after running input & output scripts
        Map<String, String> inputScriptGeneratedVariables = TemplateManagerHelper.getScriptGeneratedVariables
                (runnableInputScript);
        Map<String, String> outputScriptGeneratedVariables = TemplateManagerHelper.getScriptGeneratedVariables
                (runnableOutputScript);

        // Get input & output templates, specified in the specific Rule Templates that are specified in the
        // Business Rule
        ArrayList<Template> inputOutputTemplatesToBeUsed = (ArrayList<Template>) getTemplates(businessRuleFromScratch);
        Template[] inputOutputTemplatesArrayToBeUsed = inputOutputTemplatesToBeUsed.toArray(new Template[0]);

        // Input & Output properties to map with templated elements in templates (given + script generated replacements)
        Map<String, String> inputPropertiesToMap = businessRuleFromScratch.getProperties().getInputData();
        inputPropertiesToMap.putAll(inputScriptGeneratedVariables);
        Map<String, String> outputPropertiesToMap = businessRuleFromScratch.getProperties().getOutputData();
        outputPropertiesToMap.putAll(outputScriptGeneratedVariables);

        // Property maps array list, that should be mapped with Templates array
        ArrayList<Map<String, String>> propertiesToMap = new ArrayList<Map<String, String>>() {
            {
                add(inputPropertiesToMap);
                add(outputPropertiesToMap);
            }
        };

        // Derive either input or output artifact and put into hash map
        for (int i = 0; i < inputOutputTemplatesArrayToBeUsed.length; i++) {
            if (inputOutputTemplatesArrayToBeUsed[i].getType().equals(TemplateManagerConstants
                    .TEMPLATE_TYPE_SIDDHI_APP)) {
                // Derive SiddhiApp template
                Artifact derivedSiddhiApp = deriveSiddhiAppForBusinessRuleFromScratch(
                        inputOutputTemplatesArrayToBeUsed[i], propertiesToMap.get(i));
                // Put SiddhiApp's name and content to derivedTemplates HashMap
                if (i == 0) {
                    derivedArtifacts.put("inputArtifact",
                            derivedSiddhiApp);
                }
                if (i == 1) {
                    derivedArtifacts.put("outputArtifact",
                            derivedSiddhiApp);
                }
            }
        }
        return derivedArtifacts;
    }


    private Artifact buildSiddhiAppFromScratch(Map<String, Artifact> derivedTemplates,
                                               BusinessRuleFromScratch businessRuleFromScratch)
            throws
            TemplateManagerException {

        //read siddhi_app_template file
        ClassLoader classLoader = this.getClass().getClassLoader();
        InputStream inputStream = classLoader.getResourceAsStream("siddhi-app-template.json");
        JsonParser jsonParser = new JsonParser();
        JsonObject jsonObject = null;
        try {
            jsonObject = (JsonObject) jsonParser.parse(new InputStreamReader(inputStream, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            log.error(e.getMessage());
        }

        // Get input & Output rule template collection
        Collection<RuleTemplate> inputOutputRuleTemplates = getInputOutputRuleTemplates(businessRuleFromScratch);
        // Get properties
        BusinessRuleFromScratchProperty property = businessRuleFromScratch.getProperties();
        // Get ruleComponents
        Map<String, String[]> ruleComponents = property.getRuleComponents();
        // Get filterRules
        String[] filterRules = ruleComponents.get("filterRules");
        // Get ruleLogic
        String[] ruleLogic = ruleComponents.get("ruleLogic");
        // Replace ruleLogic templated values with filter rules
        Map<String, String> replacementValues = new HashMap<>();
        for (int i = 0; i < filterRules.length; i++) {
            replacementValues.put(Integer.toString(i + 1), filterRules[i]);
        }
        // Final rule logic
        String finalRuleLogic = TemplateManagerHelper.replaceRegex(ruleLogic[0], TemplateManagerConstants
                        .SIDDHI_APP_RULE_LOGIC_PATTERN,
                replacementValues);
        // Get Output mapping attributes
        Map<String, String> outputMappingMap = property.getOutputMappings();

        String[] outputMappingMapKeySet = outputMappingMap.keySet().toArray(new String[0]);
        StringBuilder mapping = new StringBuilder();
        // Generate output mapping string
        for (String anOutputMappingMapKeySet : outputMappingMapKeySet) {
            mapping.append(outputMappingMap.get(anOutputMappingMapKeySet))
                    .append(" as ").append(anOutputMappingMapKeySet).append(", ");
        }
        String mappingString = mapping.toString();
        mappingString = mapping.toString().replaceAll(", $", "");
        // Get ruleTemplates
        RuleTemplate[] ruleTemplates = inputOutputRuleTemplates.toArray(new RuleTemplate[0]);
        // Get input template exposed stream definition
        String inputTemplateStreamDefinition = ruleTemplates[0].getTemplates().toArray(new Template[0])[0]
                .getExposedStreamDefinition();
        // Get output template exposed stream definition
        String outputTemplateStreamDefinition = ruleTemplates[1].getTemplates().toArray(new Template[0])[0]
                .getExposedStreamDefinition();
        // Get stream name
        String inputStreamName = inputTemplateStreamDefinition.split(" ")[2].split("\\(")[0];
        // Get output stream name
        String outputStreamName = outputTemplateStreamDefinition.split(" ")[2].split("\\(")[0];

        Map<String, String> replacement = new HashMap<>();
        String siddhiAppTemplate = null;
        // Load siddhi app template
        if (jsonObject != null) {
            siddhiAppTemplate = jsonObject.get("siddhi-app-template").toString();
        }
        // Generate replacement values for template
        replacement.put("inputTemplate", derivedTemplates.get("inputArtifact").getContent());
        replacement.put("outputTemplate", derivedTemplates.get("outputArtifact").getContent());
        replacement.put("inputStreamName", inputStreamName);
        replacement.put("logic", finalRuleLogic);
        replacement.put("mapping", mappingString);
        replacement.put("outputStreamName", outputStreamName);
        // Create siddhi app to be deployed
        String content = TemplateManagerHelper.replaceRegex(siddhiAppTemplate, TemplateManagerConstants
                .TEMPLATED_ELEMENT_NAME_REGEX_PATTERN, replacement);
        // Add the businessRule name as siddhi app name
        content = content.replace("appName", businessRuleFromScratch.getUuid());
        String appType = "siddhiApp";
        Artifact siddhiApp = new Artifact(appType, content, "");

        return siddhiApp;
    }


    /**
     * Gives the list of Templates, that should be used by the given BusinessRuleFromTemplate
     *
     * @param businessRuleFromTemplate Given Business Rule
     * @return
     */
    public Collection<Template> getTemplates(BusinessRuleFromTemplate businessRuleFromTemplate)
            throws TemplateManagerException {
        RuleTemplate foundRuleTemplate = getRuleTemplate(businessRuleFromTemplate);
        // Get Templates from the found Rule Template
        Collection<Template> templates = foundRuleTemplate.getTemplates();

        return templates;
    }

    /**
     * Gets Templates from the Rule Template, mentioned in the given Business Rule from scratch
     *
     * @param businessRuleFromScratch
     * @return
     * @throws TemplateManagerException
     */
    public Collection<Template> getTemplates(BusinessRuleFromScratch businessRuleFromScratch) throws
            TemplateManagerException {

        Collection<RuleTemplate> inputOutputRuleTemplates = getInputOutputRuleTemplates(businessRuleFromScratch);

        // To store templates, from Input & Output Rule Templates
        Collection<Template> templates = new ArrayList<>();
        for (RuleTemplate ruleTemplate : inputOutputRuleTemplates) {
            // Only one Template will be present in a Rule Template
            ArrayList<Template> templateInRuleTemplate = (ArrayList<Template>) ruleTemplate.getTemplates();
            templates.add(templateInRuleTemplate.get(0));
        }

        return templates;
    }

    /**
     * Derives a SiddhiApp by replacing given SiddhiApp template's templated properties with the given values
     *
     * @param siddhiAppTemplate      Templated SiddhiApp
     * @param templatedElementValues Values for replacing templated elements, that are derived by the script from the
     *                               TemplateGroup / directly entered by the user (when no script is present)
     * @return
     */
    public Artifact deriveSiddhiApp(Template siddhiAppTemplate, Map<String, String> templatedElementValues)
            throws TemplateManagerException {
        // SiddhiApp content, that contains templated elements
        String templatedSiddhiAppString = siddhiAppTemplate.getContent();
        // Replace templated elements in SiddhiApp content
        String derivedSiddhiAppString = TemplateManagerHelper.replaceRegex(templatedSiddhiAppString,
                TemplateManagerConstants.TEMPLATED_ELEMENT_NAME_REGEX_PATTERN, templatedElementValues);
        // No exposed stream definition for SiddhiApp of type 'template'. Only present in types 'input' / 'output'
        Artifact derivedSiddhiApp = new Artifact(TemplateManagerConstants.TEMPLATE_TYPE_SIDDHI_APP,
                derivedSiddhiAppString, null);

        return derivedSiddhiApp;
    }

    /**
     * Derives an artifact, by replacing templated elements in the given siddhiAppTemplate and removing the siddhiApp
     * name
     *
     * @param siddhiAppTemplate
     * @param templatedElementValues
     * @return
     * @throws TemplateManagerException
     */
    public Artifact deriveSiddhiAppForBusinessRuleFromScratch(Template siddhiAppTemplate, Map<String, String>
            templatedElementValues)
            throws TemplateManagerException {
        String derivedSiddhiAppString;
        // SiddhiApp content, that contains templated elements
        String templatedSiddhiAppString = siddhiAppTemplate.getContent();
        // Remove name from template
        templatedSiddhiAppString = templatedSiddhiAppString.replaceFirst(TemplateManagerConstants
                .SIDDHI_APP_NAME_REGEX_PATTERN, "");
        // Replace templated elements in SiddhiApp content
        derivedSiddhiAppString = TemplateManagerHelper.replaceRegex(templatedSiddhiAppString,
                TemplateManagerConstants.TEMPLATED_ELEMENT_NAME_REGEX_PATTERN, templatedElementValues);
        // No exposed stream definition for SiddhiApp of type 'template'. Only present in types 'input' / 'output'
        Artifact derivedSiddhiApp = new Artifact(TemplateManagerConstants.TEMPLATE_TYPE_SIDDHI_APP,
                derivedSiddhiAppString, null);

        return derivedSiddhiApp;
    }

    /**
     * Gets the Rule Template, that is specified in the given Business Rule
     *
     * @param businessRuleFromTemplate
     * @return
     */
    public RuleTemplate getRuleTemplate(BusinessRuleFromTemplate businessRuleFromTemplate)
            throws TemplateManagerException {
        String templateGroupUUID = businessRuleFromTemplate.getTemplateGroupUUID();
        String ruleTemplateUUID = businessRuleFromTemplate.getRuleTemplateUUID();

        TemplateGroup foundTemplateGroup = this.availableTemplateGroups.get(templateGroupUUID);
        RuleTemplate foundRuleTemplate = null;

        // A Template Group has been found with the given UUID
        if (foundTemplateGroup != null) {
            for (RuleTemplate ruleTemplate : foundTemplateGroup.getRuleTemplates()) {
                if (ruleTemplate.getUuid().equals(ruleTemplateUUID)) {
                    foundRuleTemplate = ruleTemplate;
                    break;
                }
            }
            // If a Rule Template has been found
            if (foundRuleTemplate != null) {
                return foundRuleTemplate;
            } else {
                throw new TemplateManagerException("No rule template found with the given uuid");
            }
        } else {
            throw new TemplateManagerException("No template group found with the given uuid");
        }

    }

    /**
     * Gives input & output Rule Templates in a list, specified in the given Business Rule from scratch
     * First member of list denotes Input Rule Template
     * Second member of list denotes Output Rule Template
     *
     * @param businessRuleFromScratch
     * @return
     * @throws TemplateManagerException
     */
    public Collection<RuleTemplate> getInputOutputRuleTemplates(BusinessRuleFromScratch businessRuleFromScratch) throws
            TemplateManagerException {
        // Find the Rule Template, specified in the Business Rule
        String templateGroupUUID = businessRuleFromScratch.getTemplateGroupUUID();
        TemplateGroup foundTemplateGroup = this.availableTemplateGroups.get(templateGroupUUID);
        // Store input & output rule templates
        Collection<RuleTemplate> foundInputOutputRuleTemplates = new ArrayList<>();
        String[] inputAndOutputRuleTemplateUUIDs = new String[2];
        inputAndOutputRuleTemplateUUIDs[0] = businessRuleFromScratch.getInputRuleTemplateUUID();
        inputAndOutputRuleTemplateUUIDs[1] = businessRuleFromScratch.getOutputRuleTemplateUUID();

        // If specified Template Group is found
        if (foundTemplateGroup != null) {
            for (RuleTemplate ruleTemplate : foundTemplateGroup.getRuleTemplates()) {
                // Add only input / output Rule Templates to the list
                for (String ruleTemplateUUID : inputAndOutputRuleTemplateUUIDs) {
                    if (ruleTemplate.getUuid().equals(ruleTemplateUUID)) {
                        foundInputOutputRuleTemplates.add(ruleTemplate);
                    }
                }
            }
            if (!foundInputOutputRuleTemplates.isEmpty()) {
                return foundInputOutputRuleTemplates;
            } else {
                throw new TemplateManagerException("No input / output rule template(s) found with the given uuid");
            }
        } else {
            throw new TemplateManagerException("No template group found with the given uuid");
        }


    }

    /**
     * Saves JSON definition of the given Business Rule, to the database
     *
     * @param businessRuleFromTemplate
     * @throws TemplateManagerException,UnsupportedEncodingException,BusinessRulesDatasourceException,SQLException
     */
    public void saveBusinessRuleDefinition(String uuid, BusinessRuleFromTemplate businessRuleFromTemplate, boolean
            isDeployed) throws
            TemplateManagerException, UnsupportedEncodingException, BusinessRulesDatasourceException, SQLException {
        QueryExecutor queryExecutor = new QueryExecutor();
        byte[] businessRule = TemplateManagerHelper.businessRuleFromTemplateToJson(businessRuleFromTemplate).getBytes("UTF-8");
        // convert String into InputStream
        int deploymentState = 0;
        if (isDeployed) {
            deploymentState = 1;
        }
        queryExecutor.executeInsertQuery(uuid, businessRule, deploymentState);
    }

    /**
     * Saves JSON definition of the given Business Rule, to the database
     *
     * @param businessRuleFromScratch
     * @throws TemplateManagerException,UnsupportedEncodingException,BusinessRulesDatasourceException,SQLException
     */
    public void saveBusinessRuleDefinition(String uuid, BusinessRuleFromScratch businessRuleFromScratch, boolean
            isDeployed) throws
            TemplateManagerException, UnsupportedEncodingException, BusinessRulesDatasourceException, SQLException {
        QueryExecutor queryExecutor = new QueryExecutor();
        int deploymentState = 0;
        byte[] businessRule = TemplateManagerHelper.businessRuleFromScratchToJson(businessRuleFromScratch).getBytes("UTF-8");
        // convert String into InputStream
        if (isDeployed) {
            deploymentState = 1;
        }
        queryExecutor.executeInsertQuery(uuid, businessRule, deploymentState);
    }

    /**
     * Deploys the given Template
     *
     * @param template
     * @throws TemplateManagerException
     */
    public void deployTemplate(String uuid, Artifact template) throws TemplateManagerException {
        if (template.getType().equals(TemplateManagerConstants.TEMPLATE_TYPE_SIDDHI_APP)) {
            deploySiddhiApp(uuid, template);
        }
        // Other template types are not considered for now todo: exception
    }

    /**
     * Deploys the given Template, of type SiddhiApp
     *
     * @param siddhiAppName
     * @param siddhiApp
     * @throws TemplateManagerException
     */
    public void deploySiddhiApp(String siddhiAppName, Artifact siddhiApp) throws TemplateManagerException {
        SiddhiAppApiHelper siddhiAppApiHelper = new SiddhiAppApiHelper();
        String deploybalSiddhiApp;
//        ConfigReader configReader = new ConfigReader("business.rules");
        if (!siddhiApp.getContent().startsWith("@")) {
            deploybalSiddhiApp = siddhiApp.getContent().substring(1, siddhiApp.getContent().length() - 1);
        } else {
            deploybalSiddhiApp = siddhiApp.getContent();
        }
        siddhiAppApiHelper.deploySiddhiApp("localhost:9090", deploybalSiddhiApp);
        // TODO: 10/8/17 handle the successfully deployed case and failed to deploy case
    }

    /**
     * Overwrites JSON definition of the Business Rule that has the given id,
     * with the given Business Rule
     *
     * @param uuid
     * @param businessRuleFromTemplate
     * @throws TemplateManagerException
     */
    public void overwriteBusinessRuleDefinition(String uuid, BusinessRuleFromTemplate businessRuleFromTemplate, boolean
            isDeployed) throws TemplateManagerException, UnsupportedEncodingException, BusinessRulesDatasourceException,
            SQLException {
        QueryExecutor queryExecutor = new QueryExecutor();
        int deploymentState = 0;
        byte[] businessRule = businessRuleFromTemplate.toString().getBytes("UTF-8");
        if (isDeployed) {
            deploymentState = 1;
        }
        queryExecutor.executeUpdateBusinessRuleQuery(uuid, businessRule, deploymentState);
    }

    public void overwriteBusinessRuleDefinition(String uuid, BusinessRuleFromScratch businessRuleFromScratch,
                                                boolean isDeployed) throws
            TemplateManagerException, UnsupportedEncodingException, BusinessRulesDatasourceException, SQLException {
        QueryExecutor queryExecutor = new QueryExecutor();
        int deploymentState = 0;
        byte[] businessRule = businessRuleFromScratch.toString().getBytes("UTF-8");
        if (isDeployed) {
            deploymentState = 1;
        }
        queryExecutor.executeUpdateBusinessRuleQuery(uuid, businessRule, deploymentState);
    }

    /**
     * Updates the deployment of the given Template
     *
     * @param template
     * @throws TemplateManagerException
     */
    public boolean updateDeployedArtifact(String uuid, Artifact template) throws TemplateManagerException {
        boolean isDeployed;
        if (template.getType().equals(TemplateManagerConstants.TEMPLATE_TYPE_SIDDHI_APP)) {
            isDeployed = updateDeployedSiddhiApp(uuid, template);
            return isDeployed;
        }
        return false;
    }

    /**
     * Updates the deployment of the given Template, of type SiddhiApp
     *
     * @param siddhiApp
     * @throws TemplateManagerException
     */
    public boolean updateDeployedSiddhiApp(String uuid, Artifact siddhiApp) throws TemplateManagerException {
        boolean isDeployed;
        SiddhiAppApiHelper siddhiAppApiHelper = new SiddhiAppApiHelper();
        isDeployed = siddhiAppApiHelper.update("localhost:9090", siddhiApp.getContent());
        // TODO: 10/8/17 handle the successfully deployed case and failed to deploy case
        return isDeployed;
    }

    /**
     * Gets types and UUIDs of the Templates, that belong to the given BusinessRuleFromTemplate
     *
     * @param businessRuleFromTemplate
     * @return Collection of String array entries, of which elements are as following :
     * [0]-TemplateType & [1]-TemplateUUID
     */
    public Collection<String[]> getTemplateTypesAndUUIDs(BusinessRuleFromTemplate businessRuleFromTemplate)
            throws TemplateManagerException {
        // To store found Template UUIDs and types
        // Each entry's [0]-TemplateType [1]-TemplateUUID
        Collection<String[]> templateTypesAndUUIDs = new ArrayList();

        // UUIDs and denoted Artifacts
        Map<String, Artifact> derivedTemplates = deriveArtifacts(businessRuleFromTemplate);
        for (Template derivedTemplate : derivedTemplates.values()) {
            // If Template is a SiddhiApp
            if (derivedTemplate.getType().equals(TemplateManagerConstants.TEMPLATE_TYPE_SIDDHI_APP)) {
                try {
                    String siddhiAppName = TemplateManagerHelper.getSiddhiAppName(derivedTemplate);
                    // Add type and name of template
                    templateTypesAndUUIDs.add(new String[]{TemplateManagerConstants.TEMPLATE_TYPE_SIDDHI_APP,
                            siddhiAppName});
                } catch (TemplateManagerException e) {
                    log.error(e.getMessage(), e);
                }
            }
            // Other template types are not considered for now
        }

        return templateTypesAndUUIDs;
    }

    /**
     * Undeploys the Template with the given UUID, according to the given template type
     *
     * @param templateType
     * @param uuid
     * @throws TemplateManagerException
     */
    public void undeployArtifact(String templateType, String uuid) throws TemplateManagerException {
        // If Template is a SiddhiApp
        if (templateType.equals(TemplateManagerConstants.TEMPLATE_TYPE_SIDDHI_APP)) {
            undeploySiddhiApp(uuid);
        }
        // other Template types are not considered for now
    }

    /**
     * Undeploys the Template of type SiddhiApp, with the given UUID
     *
     * @param uuid
     * @throws TemplateManagerException
     */
    public boolean undeploySiddhiApp(String uuid) throws TemplateManagerException {
        SiddhiAppApiHelper siddhiAppApiHelper = new SiddhiAppApiHelper();
        Boolean isSucessfullyUndeployed;
        isSucessfullyUndeployed = siddhiAppApiHelper.delete("localhost:9090", uuid);
        return isSucessfullyUndeployed;
    }

    /**
     * Deletes the JSON definition of the Business Rule, that has the given UUID
     *
     * @param uuid
     * @throws TemplateManagerException
     */
    public void removeBusinessRuleDefinition(String uuid) throws TemplateManagerException,
            BusinessRulesDatasourceException, SQLException {
        QueryExecutor queryExecutor = new QueryExecutor();
        queryExecutor.executeDeleteQuery(uuid);
    }

    //////////// insert anything on top of this //////////

    /**
     * Finds the Template Group with the given name
     *
     * @param templateGroupName
     * @return
     * @throws TemplateManagerException
     */
    public TemplateGroup findTemplateGroup(String templateGroupName) throws TemplateManagerException {
        for (String availableTemplateGroupName : availableTemplateGroups.keySet()) {
            if (availableTemplateGroupName.equals(templateGroupName)) {
                return availableTemplateGroups.get(availableTemplateGroupName);
            }
        }

        throw new TemplateManagerException("No Template Group found with the name : " + templateGroupName);
    }
}