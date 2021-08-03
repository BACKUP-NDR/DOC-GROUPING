package org.recap.util;

import com.google.common.collect.Lists;
import org.apache.camel.ProducerTemplate;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.recap.PropertyKeyConstants;
import org.recap.ScsbCommonConstants;
import org.recap.ScsbConstants;
import org.recap.matchingalgorithm.MatchingCounter;
import org.recap.model.jpa.MatchingBibEntity;
import org.recap.model.jpa.MatchingMatchPointsEntity;
import org.recap.model.jpa.ReportDataEntity;
import org.recap.model.jpa.ReportEntity;
import org.recap.repository.jpa.*;
import org.recap.service.accession.SolrIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.solr.core.SolrTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import javax.annotation.Resource;
import java.io.IOException;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.recap.ScsbConstants.*;

/**
 * Created by angelind on 4/11/16.
 */
@Component
public class MatchingAlgorithmUtil {

    private static final Logger logger = LoggerFactory.getLogger(MatchingAlgorithmUtil.class);
    @Autowired
    SolrIndexService solrIndexService;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    private ProducerTemplate producerTemplate;
    @Autowired
    private MatchingMatchPointsDetailsRepository matchingMatchPointsDetailsRepository;
    @Autowired
    private MatchingBibDetailsRepository matchingBibDetailsRepository;
    @Resource(name = "recapSolrTemplate")
    private SolrTemplate solrTemplate;
    @Autowired
    private SolrQueryBuilder solrQueryBuilder;
    @Autowired
    private ReportDetailRepository reportDetailRepository;
    @Autowired
    private ReportDataDetailsRepository reportDataDetailsRepository;
    private String and = " AND ";
    private String coreParentFilterQuery = "{!parent which=\"ContentType:parent\"}";
    @Value("${" + PropertyKeyConstants.MATCHING_REPORT_HEADER_VALUE_LENGTH + "}")
    private Integer matchingHeaderValueLength;
    @Autowired
    private CommonUtil commonUtil;
    @Autowired
    private BibliographicDetailsRepository bibliographicDetailsRepository;

    /**
     * Gets report detail repository.
     *
     * @return the report detail repository
     */
    public ReportDetailRepository getReportDetailRepository() {
        return reportDetailRepository;
    }

    /**
     * This method populates and save the reports for single match bibs.
     *
     * @param batchSize             the batch size
     * @param matching              the matching
     * @param institutionCounterMap
     * @return the single match bibs and save report
     */
    public Map<String, Integer> getSingleMatchBibsAndSaveReport(Integer batchSize, String matching, Map<String, Integer> institutionCounterMap) {
        Map<String, Set<Integer>> criteriaMap = new HashMap<>();
        Map<Integer, MatchingBibEntity> bibEntityMap = new HashMap<>();
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        List<Integer> singleMatchBibIdsBasedOnMatching = matchingBibDetailsRepository.getSingleMatchBibIdsBasedOnMatching(matching);
        stopWatch.stop();
        logger.info("Time taken to fetch {} from db : {} ", matching, stopWatch.getTotalTimeSeconds());
        logger.info("Total {}  : {} ", matching, singleMatchBibIdsBasedOnMatching.size());

        if (CollectionUtils.isNotEmpty(singleMatchBibIdsBasedOnMatching)) {
            List<List<Integer>> bibIdLists = Lists.partition(singleMatchBibIdsBasedOnMatching, batchSize);
            logger.info("Total {} list : {} ", matching, bibIdLists.size());
            for (Iterator<List<Integer>> iterator = bibIdLists.iterator(); iterator.hasNext(); ) {
                List<Integer> bibIds = iterator.next();
                List<MatchingBibEntity> matchingBibEntities = matchingBibDetailsRepository.getBibEntityBasedOnBibIds(bibIds);
                if (CollectionUtils.isNotEmpty(matchingBibEntities)) {
                    for (Iterator<MatchingBibEntity> matchingBibEntityIterator = matchingBibEntities.iterator(); matchingBibEntityIterator.hasNext(); ) {
                        MatchingBibEntity matchingBibEntity = matchingBibEntityIterator.next();
                        Integer bibId = matchingBibEntity.getBibId();
                        String matchCriteriaValue = getMatchCriteriaValue(matching, matchingBibEntity);
                        if (!bibEntityMap.containsKey(bibId)) {
                            bibEntityMap.put(bibId, matchingBibEntity);
                        }
                        populateCriteriaMap(criteriaMap, bibId, matchCriteriaValue);
                    }
                }
            }

            Set<String> criteriaValueSet = new HashSet<>();
            for (Iterator<String> iterator = criteriaMap.keySet().iterator(); iterator.hasNext(); ) {
                String criteriaValue = iterator.next();
                if (!criteriaValueSet.contains(criteriaValue) && criteriaMap.get(criteriaValue).size() > 1) {
                    StringBuilder matchPointValue = new StringBuilder();
                    criteriaValueSet.add(criteriaValue);
                    Set<Integer> bibIds = criteriaMap.get(criteriaValue);
                    Set<Integer> tempBibIds = new HashSet<>(bibIds);
                    for (Integer bibId : bibIds) {
                        MatchingBibEntity matchingBibEntity = bibEntityMap.get(bibId);
                        matchPointValue.append(StringUtils.isNotBlank(matchPointValue.toString()) ? "," : "").append(getMatchCriteriaValue(matching, matchingBibEntity));
                        String[] criteriaValueList = matchPointValue.toString().split(",");
                        tempBibIds.addAll(getBibIdsForCriteriaValue(criteriaMap, criteriaValueSet, criteriaValue, matching, criteriaValueList, bibEntityMap, matchPointValue));
                    }
                    List<Integer> tempBibIdList = new ArrayList<>(tempBibIds);
                    saveReportForSingleMatch(matchPointValue.toString(), tempBibIdList, matching, bibEntityMap, false, institutionCounterMap);
                }
            }
        }
        return institutionCounterMap;
    }

    /**
     * Process pending matching bibs map.
     *
     * @param matchingBibEntityList the matching bib entity list
     * @param matchingBibIds        the matching bib ids
     * @param institutionCounterMap
     * @return the map
     */
    public Map processPendingMatchingBibs(List<MatchingBibEntity> matchingBibEntityList, Set<Integer> matchingBibIds, Map<String, Integer> institutionCounterMap) {
        if (CollectionUtils.isNotEmpty(matchingBibEntityList)) {
            for (MatchingBibEntity matchingBibEntity : matchingBibEntityList) {
                if (!matchingBibIds.contains(matchingBibEntity.getId())) {
                    Map<Integer, MatchingBibEntity> matchingBibEntityMap = new HashMap<>();
                    String matchPointValue = "";
                    String query = "";
                    if (matchingBibEntity.getMatching().equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_OCLC)) {
                        matchPointValue = matchingBibEntity.getOclc();
                        if (StringUtils.isNotBlank(matchPointValue))
                            query = solrQueryBuilder.solrQueryForInitialMatching(ScsbCommonConstants.MATCH_POINT_FIELD_OCLC, Arrays.asList(matchPointValue.split(",")));
                    } else if (matchingBibEntity.getMatching().equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_ISBN)) {
                        matchPointValue = matchingBibEntity.getIsbn();
                        if (StringUtils.isNotBlank(matchPointValue))
                            query = solrQueryBuilder.solrQueryForInitialMatching(ScsbCommonConstants.MATCH_POINT_FIELD_ISBN, Arrays.asList(matchPointValue.split(",")));
                    } else if (matchingBibEntity.getMatching().equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_ISSN)) {
                        matchPointValue = matchingBibEntity.getIssn();
                        if (StringUtils.isNotBlank(matchPointValue))
                            query = solrQueryBuilder.solrQueryForInitialMatching(ScsbCommonConstants.MATCH_POINT_FIELD_ISSN, Arrays.asList(matchPointValue.split(",")));
                    } else if (matchingBibEntity.getMatching().equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_LCCN)) {
                        matchPointValue = matchingBibEntity.getLccn();
                        query = solrQueryBuilder.solrQueryForInitialMatching(ScsbCommonConstants.MATCH_POINT_FIELD_LCCN, matchPointValue);
                    }
                    List<Integer> bibIds = getBibsFromSolr(query);
                    if (bibIds.size() > 1) {
                        List<MatchingBibEntity> bibEntities = matchingBibDetailsRepository.findByMatchingAndBibIdIn(matchingBibEntity.getMatching(), bibIds);
                        for (MatchingBibEntity bibEntity : bibEntities) {
                            matchingBibEntityMap.put(bibEntity.getBibId(), bibEntity);
                            matchingBibIds.add(bibEntity.getId());
                        }
                        saveReportForSingleMatch(matchPointValue, bibIds, matchingBibEntity.getMatching(), matchingBibEntityMap, true, institutionCounterMap);
                    }
                }
            }
        }

        return institutionCounterMap;
    }

    private List<Integer> getBibsFromSolr(String query) {
        List<Integer> bibIds = new ArrayList<>();
        try {
            SolrQuery solrQuery = new SolrQuery(query);
            solrQuery.setFields(ScsbCommonConstants.BIB_ID);
            QueryResponse queryResponse = solrTemplate.getSolrClient().query(solrQuery);
            SolrDocumentList solrDocumentList = queryResponse.getResults();
            long numFound = solrDocumentList.getNumFound();
            if (numFound > solrDocumentList.size()) {
                solrQuery.setRows((int) numFound);
                queryResponse = solrTemplate.getSolrClient().query(solrQuery);
                solrDocumentList = queryResponse.getResults();
            }
            for (Iterator<SolrDocument> iterator = solrDocumentList.iterator(); iterator.hasNext(); ) {
                SolrDocument solrDocument = iterator.next();
                Integer bibId = (Integer) solrDocument.getFieldValue(ScsbCommonConstants.BIB_ID);
                bibIds.add(bibId);
            }
        } catch (Exception e) {
            logger.error(ScsbCommonConstants.LOG_ERROR, e);
        }
        return bibIds;
    }

    /**
     * This method gets bib ids for the given criteria values.
     *
     * @param criteriaMap       the criteria map
     * @param criteriaValueSet  the criteria value set
     * @param criteriaValue     the criteria value
     * @param matching          the matching
     * @param criteriaValueList the criteria value list
     * @param bibEntityMap      the bib entity map
     * @param matchPointValue   the match point value
     * @return the bib ids for criteria value
     */
    public Set<Integer> getBibIdsForCriteriaValue(Map<String, Set<Integer>> criteriaMap, Set<String> criteriaValueSet, String criteriaValue,
                                                  String matching, String[] criteriaValueList, Map<Integer, MatchingBibEntity> bibEntityMap, StringBuilder matchPointValue) {
        Set<Integer> tempBibIdSet = new HashSet<>();
        for (String value : criteriaValueList) {
            criteriaValueSet.add(value);
            if (!value.equalsIgnoreCase(criteriaValue)) {
                Set<Integer> bibIdSet = criteriaMap.get(value);
                if (org.apache.commons.collections.CollectionUtils.isNotEmpty(bibIdSet)) {
                    for (Integer bibId : bibIdSet) {
                        MatchingBibEntity matchingBibEntity = bibEntityMap.get(bibId);
                        String matchCriteriaValue = getMatchCriteriaValue(matching, matchingBibEntity);
                        String[] matchCriteriaValueList = matchCriteriaValue.split(",");
                        for (String matchingValue : matchCriteriaValueList) {
                            if (!criteriaValueSet.contains(matchingValue)) {
                                matchPointValue.append(StringUtils.isNotBlank(matchPointValue.toString()) ? "," : "").append(matchingValue);
                                criteriaValueSet.add(matchingValue);
                            }
                        }
                    }
                    tempBibIdSet.addAll(bibIdSet);
                }
            }
        }
        return tempBibIdSet;
    }

    /**
     * This method replaces diacritics(~= accents) characters by replacing them to normal characters in title.
     *
     * @param title the title
     * @return the string
     */
    public String normalizeDiacriticsInTitle(String title) {
        String normalizedTitle = Normalizer.normalize(title, Normalizer.Form.NFD);
        normalizedTitle = normalizedTitle.replaceAll("[^\\p{ASCII}]", "");
        normalizedTitle = normalizedTitle.replaceAll("\\p{M}", "");
        return normalizedTitle;
    }

    /**
     * This method saves report for single match based on the criteria values (oclc,isbn,issn and lccn).
     *
     * @param criteriaValue         the criteria value
     * @param bibIdList             the bib id list
     * @param criteria              the criteria
     * @param matchingBibEntityMap  the matching bib entity map
     * @param isPendingBibs         the is pending bibs
     * @param institutionCounterMap
     * @return the map
     */
    public Map<String, Integer> saveReportForSingleMatch(String criteriaValue, List<Integer> bibIdList, String criteria, Map<Integer, MatchingBibEntity> matchingBibEntityMap, boolean isPendingBibs, Map<String, Integer> institutionCounterMap) {
        List<ReportDataEntity> reportDataEntities = new ArrayList<>();
        Set<String> owningInstSet = new HashSet<>();
        Set<String> materialTypeSet = new HashSet<>();
        List<Integer> bibIds = new ArrayList<>();
        List<String> owningInstList = new ArrayList<>();
        List<String> materialTypeList = new ArrayList<>();
        Map<String, String> titleMap = new HashMap<>();
        List<ReportEntity> reportEntitiesToSave = new ArrayList<>();
        List<String> owningInstBibIds = new ArrayList<>();


        int index = 0;
        for (Iterator<Integer> iterator = bibIdList.iterator(); iterator.hasNext(); ) {
            Integer bibId = iterator.next();
            MatchingBibEntity matchingBibEntity = matchingBibEntityMap.get(bibId);
            if (matchingBibEntity != null) {
                owningInstSet.add(matchingBibEntity.getOwningInstitution());
                owningInstList.add(matchingBibEntity.getOwningInstitution());
                owningInstBibIds.add(matchingBibEntity.getOwningInstBibId());
                bibIds.add(bibId);
                materialTypeList.add(matchingBibEntity.getMaterialType());
                materialTypeSet.add(matchingBibEntity.getMaterialType());
                index = index + 1;
                if (StringUtils.isNotBlank(matchingBibEntity.getTitle())) {
                    String titleHeader = ScsbCommonConstants.TITLE + index;
                    getReportDataEntity(titleHeader, matchingBibEntity.getTitle(), reportDataEntities);
                    titleMap.put(titleHeader, matchingBibEntity.getTitle());
                }
            }
        }

        if (owningInstSet.size() > 1) {
            ReportEntity reportEntity = new ReportEntity();
            String fileName;
            String criteriaForFileName = criteria.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_OCLC) ? ScsbCommonConstants.OCLC_CRITERIA : criteria;
            if (isPendingBibs) {
                fileName = ScsbConstants.MATCHING_PENDING_BIBS;
            } else {
                fileName = criteriaForFileName;
            }
            reportEntity.setFileName(fileName);
            reportEntity.setInstitutionName(ScsbCommonConstants.ALL_INST);
            reportEntity.setCreatedDate(new Date());
            Set<String> unMatchingTitleHeaderSet = getMatchingAndUnMatchingBibsOnTitleVerification(titleMap);
            if (CollectionUtils.isNotEmpty(unMatchingTitleHeaderSet)) {

                reportEntitiesToSave.add(processReportsForUnMatchingTitles(criteriaForFileName, titleMap, bibIds,
                        materialTypeList, owningInstList, owningInstBibIds,
                        criteriaValue, unMatchingTitleHeaderSet));

            }
            if (materialTypeSet.size() != 1) {
                reportEntity.setType(ScsbConstants.MATERIAL_TYPE_EXCEPTION);
            } else {
                reportEntity.setType(ScsbConstants.SINGLE_MATCH);
                owningInstList.forEach(owningInst -> institutionCounterMap.replace(owningInst, +1));
            }

            getReportDataEntityList(reportDataEntities, owningInstList, bibIds, materialTypeList, owningInstBibIds);

            getReportDataEntity(criteriaForFileName, criteriaValue, reportDataEntities);

            reportEntity.addAll(reportDataEntities);
            reportEntitiesToSave.add(reportEntity);
            if (!isPendingBibs) {
                Map matchingBibMap = new HashMap();
                matchingBibMap.put(ScsbCommonConstants.STATUS, ScsbCommonConstants.COMPLETE_STATUS);
                matchingBibMap.put(ScsbConstants.MATCHING_BIB_IDS, bibIds);
                producerTemplate.sendBody("scsbactivemq:queue:updateMatchingBibEntityQ", matchingBibMap);
            }
        }
        if (CollectionUtils.isNotEmpty(reportEntitiesToSave)) {
            producerTemplate.sendBody("scsbactivemq:queue:saveMatchingReportsQ", reportEntitiesToSave);
        }
        return institutionCounterMap;
    }

    /**
     * This method gets set of matched and un-matched bibs on title verification.
     *
     * @param titleMap the title map
     * @return the matched and un matched bibs on title verification
     */
    public Set<String> getMatchingAndUnMatchingBibsOnTitleVerification(Map<String, String> titleMap) {

        Set<String> unMatchingTitleHeaderSet = new HashSet<>();
        if (titleMap != null) {
            List<String> titleHeaders = new ArrayList(titleMap.keySet());
            for (int i = 0; i < titleMap.size(); i++) {
                for (int j = i + 1; j < titleMap.size(); j++) {
                    String titleHeader1 = titleHeaders.get(i);
                    String titleHeader2 = titleHeaders.get(j);
                    String title1 = titleMap.get(titleHeader1);
                    String title2 = titleMap.get(titleHeader2);
                    title1 = getTitleToMatch(title1);
                    title2 = getTitleToMatch(title2);
                    if (!(title1.equalsIgnoreCase(title2))) {
                        unMatchingTitleHeaderSet.add(titleHeader1);
                        unMatchingTitleHeaderSet.add(titleHeader2);
                    }
                }
            }
        }
        return unMatchingTitleHeaderSet;
    }

    /**
     * This method gets matched title for  the given title.
     *
     * @param title the title
     * @return the title to match
     */
    public String getTitleToMatch(String title) {
        title = normalizeDiacriticsInTitle(title.trim());
        title = title.replaceAll("[^\\w\\s]", "").trim();
        title = title.replaceAll("\\s{2,}", " ");
        String titleToMatch = "";
        if (StringUtils.isNotBlank(title)) {
            String[] titleArray = title.split(" ");
            int count = 0;
            for (int j = 0; j < titleArray.length; j++) {
                String tempTitle = titleArray[j];
                if (!("a".equalsIgnoreCase(tempTitle) || "an".equalsIgnoreCase(tempTitle) || "the".equalsIgnoreCase(tempTitle))) {
                    titleToMatch = getTitleToMatch(titleToMatch, count, tempTitle);
                    count = count + 1;
                } else {
                    if (j != 0) {
                        titleToMatch = getTitleToMatch(titleToMatch, count, tempTitle);
                        count = count + 1;
                    }
                }
                if (count == 4) {
                    break;
                }
            }
        }
        return titleToMatch.replaceAll("\\s", "");
    }

    private String getTitleToMatch(String titleToMatch, int count, String tempTitle) {
        if (count == 0) {
            titleToMatch = tempTitle;
        } else {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append(titleToMatch);
            stringBuilder.append(" ");
            stringBuilder.append(tempTitle);
            titleToMatch = stringBuilder.toString();
        }
        return titleToMatch;
    }

    /**
     * This method gets a list of report data entities for matching algorithm reports.
     *
     * @param reportDataEntities the report data entities
     * @param owningInstSet      the owning inst set
     * @param bibIds             the bib ids
     * @param materialTypes      the material types
     * @param owningInstBibIds   the owning inst bib ids
     */
    public void getReportDataEntityList(List<ReportDataEntity> reportDataEntities, Collection owningInstSet, Collection bibIds, Collection materialTypes, List<String> owningInstBibIds) {
        checkAndAddReportDataEntities(reportDataEntities, bibIds, ScsbCommonConstants.BIB_ID);
        checkAndAddReportDataEntities(reportDataEntities, owningInstSet, ScsbCommonConstants.OWNING_INSTITUTION);
        checkAndAddReportDataEntities(reportDataEntities, materialTypes, ScsbConstants.MATERIAL_TYPE);
        checkAndAddReportDataEntities(reportDataEntities, owningInstBibIds, ScsbCommonConstants.OWNING_INSTITUTION_BIB_ID);
    }

    private void checkAndAddReportDataEntities(List<ReportDataEntity> reportDataEntities, Collection bibIds, String bibId) {
        if (CollectionUtils.isNotEmpty(bibIds)) {
            ReportDataEntity bibIdReportDataEntity = getReportDataEntityForCollectionValues(bibIds, bibId);
            reportDataEntities.add(bibIdReportDataEntity);
        }
    }

    /**
     * This method gets report data entity for collection values.
     *
     * @param headerValues the header values
     * @param headerName   the header name
     * @return the report data entity for collection values
     */
    public ReportDataEntity getReportDataEntityForCollectionValues(Collection headerValues, String headerName) {
        ReportDataEntity bibIdReportDataEntity = new ReportDataEntity();
        bibIdReportDataEntity.setHeaderName(headerName);
        String joinedHeaderValue = StringUtils.join(headerValues, ",");
        if (StringUtils.isNotBlank(joinedHeaderValue)) {
            setTrimmedHeaderValue(headerName, bibIdReportDataEntity, joinedHeaderValue);
        } else {
            bibIdReportDataEntity.setHeaderValue(joinedHeaderValue);
        }
        return bibIdReportDataEntity;
    }

    private void setTrimmedHeaderValue(String headerName, ReportDataEntity bibIdReportDataEntity, String joinedHeaderValue) {
        int headerValueLength = joinedHeaderValue.length();
        logger.info("Matching header value length : {}", headerValueLength);
        if (headerValueLength <= matchingHeaderValueLength) {
            bibIdReportDataEntity.setHeaderValue(joinedHeaderValue);
        } else {
            logger.debug("Header value : {} ", joinedHeaderValue);
            logger.info("Maximum Header value crossed : {} for header name : {} and started truncating", joinedHeaderValue.length(), headerName);
            String substring = StringUtils.substring(joinedHeaderValue, 0, matchingHeaderValueLength);
            bibIdReportDataEntity.setHeaderValue(StringUtils.substringBeforeLast(substring, ","));
        }
    }

    /**
     * This method populates bib id with matching criteria values.
     *
     * @param criteria1Map        the criteria 1 map
     * @param matchingBibEntities the matching bib entities
     * @param matchCriteria1      the match criteria 1
     * @param bibEntityMap        the bib entity map
     */
    public void populateBibIdWithMatchingCriteriaValue(Map<String, Set<Integer>> criteria1Map, List<MatchingBibEntity> matchingBibEntities, String matchCriteria1, Map<Integer, MatchingBibEntity> bibEntityMap) {
        for (Iterator<MatchingBibEntity> iterator = matchingBibEntities.iterator(); iterator.hasNext(); ) {
            MatchingBibEntity matchingBibEntity = iterator.next();
            Integer bibId = matchingBibEntity.getBibId();
            String matching = matchingBibEntity.getMatching();
            if (!bibEntityMap.containsKey(bibId)) {
                bibEntityMap.put(bibId, matchingBibEntity);
            }
            if (matching.equalsIgnoreCase(matchCriteria1)) {
                String criteriaValue1 = getMatchCriteriaValue(matchCriteria1, matchingBibEntity);
                populateCriteriaMap(criteria1Map, bibId, criteriaValue1);
            }
        }
    }

    /**
     * This method populates the bib ids for matching match point values.
     *
     * @param criteriaMap the criteria map
     * @param bibId       the bib id
     * @param value       the value
     */
    public void populateCriteriaMap(Map<String, Set<Integer>> criteriaMap, Integer bibId, String value) {

        String[] criteriaValues = value.split(",");
        for (String criteriaValue : criteriaValues) {
            if (StringUtils.isNotBlank(criteriaValue)) {
                if (criteriaMap.containsKey(criteriaValue)) {
                    Set<Integer> bibIds = criteriaMap.get(criteriaValue);
                    Set<Integer> bibIdSet = new HashSet<>(bibIds);
                    bibIdSet.add(bibId);
                    criteriaMap.put(criteriaValue, bibIdSet);
                } else {
                    Set<Integer> bibIdSet = new HashSet<>();
                    bibIdSet.add(bibId);
                    criteriaMap.put(criteriaValue, bibIdSet);
                }
            }
        }
    }

    /**
     * This method gets match point criteria value.
     *
     * @param matchCriteria     the match criteria
     * @param matchingBibEntity the matching bib entity
     * @return the match criteria value
     */
    public String getMatchCriteriaValue(String matchCriteria, MatchingBibEntity matchingBibEntity) {
        if (matchCriteria.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_OCLC)) {
            return matchingBibEntity.getOclc();
        } else if (matchCriteria.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_ISBN)) {
            return matchingBibEntity.getIsbn();
        } else if (matchCriteria.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_ISSN)) {
            return matchingBibEntity.getIssn();
        } else if (matchCriteria.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_LCCN)) {
            return matchingBibEntity.getLccn();
        }
        return "";
    }

    /**
     * This method populates and save report entity for multi match scenario in matching algorithm.
     *
     * @param bibIds       the bib ids
     * @param bibEntityMap the bib entity map
     * @param header1      the header 1
     * @param header2      the header 2
     * @param oclcNumbers  the oclc numbers
     * @param isbns        the isbns
     * @return the map
     */
    public Map<String, Integer> populateAndSaveReportEntity(Set<Integer> bibIds, Map<Integer, MatchingBibEntity> bibEntityMap, String header1, String header2, String oclcNumbers, String isbns, Map<String, Integer> institutionCounterMap) {
        ReportEntity reportEntity = new ReportEntity();
        Set<String> owningInstSet = new HashSet<>();
        List<ReportDataEntity> reportDataEntities = new ArrayList<>();
        reportEntity.setFileName(header1 + "," + header2);
        reportEntity.setCreatedDate(new Date());
        reportEntity.setInstitutionName(ScsbCommonConstants.ALL_INST);
        List<String> owningInstList = new ArrayList<>();
        List<Integer> bibIdList = new ArrayList<>();
        List<String> materialTypeList = new ArrayList<>();
        Set<String> materialTypes = new HashSet<>();
        List<String> owningInstBibIds = new ArrayList<>();


        for (Iterator<Integer> integerIterator = bibIds.iterator(); integerIterator.hasNext(); ) {
            Integer bibId = integerIterator.next();
            MatchingBibEntity matchingBibEntity = bibEntityMap.get(bibId);
            owningInstSet.add(matchingBibEntity.getOwningInstitution());
            owningInstList.add(matchingBibEntity.getOwningInstitution());
            bibIdList.add(matchingBibEntity.getBibId());
            materialTypes.add(matchingBibEntity.getMaterialType());
            materialTypeList.add(matchingBibEntity.getMaterialType());
            owningInstBibIds.add(matchingBibEntity.getOwningInstBibId());
        }
        if (materialTypes.size() == 1) {
            reportEntity.setType(ScsbConstants.MULTI_MATCH);
        } else {
            reportEntity.setType(ScsbConstants.MATERIAL_TYPE_EXCEPTION);
        }
        if (owningInstSet.size() > 1) {
            getReportDataEntityList(reportDataEntities, owningInstList, bibIdList, materialTypeList, owningInstBibIds);
            owningInstList.forEach(owningInst -> institutionCounterMap.replace(owningInst, +1));
            if (StringUtils.isNotBlank(oclcNumbers)) {
                getReportDataEntity(header1, oclcNumbers, reportDataEntities);
            }
            if (StringUtils.isNotBlank(isbns)) {
                getReportDataEntity(header2, isbns, reportDataEntities);
            }
            reportEntity.addAll(reportDataEntities);
            Map matchingBibMap = new HashMap();
            matchingBibMap.put(ScsbCommonConstants.STATUS, ScsbCommonConstants.COMPLETE_STATUS);
            matchingBibMap.put(ScsbConstants.MATCHING_BIB_IDS, bibIdList);
            producerTemplate.sendBody("scsbactivemq:queue:updateMatchingBibEntityQ", matchingBibMap);
            producerTemplate.sendBody("scsbactivemq:queue:saveMatchingReportsQ", Arrays.asList(reportEntity));
        }
        return institutionCounterMap;
    }

    /**
     * This method gets report data entity.
     *
     * @param headerName         the header 1
     * @param headerValues       the header values
     * @param reportDataEntities the report data entities
     */
    public void getReportDataEntity(String headerName, String headerValues, List<ReportDataEntity> reportDataEntities) {
        ReportDataEntity criteriaReportDataEntity = new ReportDataEntity();
        if (headerValues.length() > 10000) {
            logger.info(" Length of the header name with size greater than 10000- {} - Value - {} - size - {}", headerName, headerValues, headerValues.length());
            headerValues = headerValues.substring(0, 9996) + "...";
        }
        criteriaReportDataEntity.setHeaderName(headerName);
        criteriaReportDataEntity.setHeaderValue(headerValues);
        reportDataEntities.add(criteriaReportDataEntity);
    }

    /**
     * This method process reports for the bibs which came into matching algorithm but differs in title.
     *
     * @param fileName                 the file name
     * @param titleMap                 the title map
     * @param bibIds                   the bib ids
     * @param materialTypes            the material types
     * @param owningInstitutions       the owning institutions
     * @param owningInstBibIds         the owning inst bib ids
     * @param matchPointValue          the match point value
     * @param unMatchingTitleHeaderSet the un matching title header set
     * @return the report entity
     */
    public ReportEntity processReportsForUnMatchingTitles(String fileName, Map<String, String> titleMap, List<Integer> bibIds, List<String> materialTypes, List<String> owningInstitutions,
                                                          List<String> owningInstBibIds, String matchPointValue, Set<String> unMatchingTitleHeaderSet) {
        ReportEntity unMatchReportEntity = buildReportEntity(fileName);
        List<ReportDataEntity> reportDataEntityList = new ArrayList<>();
        List<String> bibIdList = new ArrayList<>();
        List<String> materialTypeList = new ArrayList<>();
        List<String> owningInstitutionList = new ArrayList<>();
        List<String> owningInstBibIdList = new ArrayList<>();

        prepareReportForUnMatchingTitles(titleMap, bibIds, materialTypes, owningInstitutions, owningInstBibIds, unMatchingTitleHeaderSet, reportDataEntityList, bibIdList, materialTypeList, owningInstitutionList, owningInstBibIdList);

        getReportDataEntityList(reportDataEntityList, owningInstitutionList, bibIdList, materialTypeList, owningInstBibIdList);

        if (StringUtils.isNotBlank(matchPointValue)) {
            getReportDataEntity(fileName, matchPointValue, reportDataEntityList);
        }
        unMatchReportEntity.addAll(reportDataEntityList);
        return unMatchReportEntity;
    }

    public ReportEntity buildReportEntity(String fileName) {
        ReportEntity unMatchReportEntity = new ReportEntity();
        unMatchReportEntity.setType("TitleException");
        unMatchReportEntity.setCreatedDate(new Date());
        unMatchReportEntity.setInstitutionName(ScsbCommonConstants.ALL_INST);
        unMatchReportEntity.setFileName(fileName);
        return unMatchReportEntity;
    }

    /**
     * This method prepares reports for the bibs which came into matching algorithm but differs in title
     *
     * @param titleMap                 the title map
     * @param bibIds                   the bib ids
     * @param materialTypes            the material types
     * @param owningInstitutions       the owning institutions
     * @param owningInstBibIds         the owning inst bib ids
     * @param unMatchingTitleHeaderSet the un matching title header set
     * @param reportDataEntityList     the report data entity list
     * @param bibIdList                the bib id list
     * @param materialTypeList         the material type list
     * @param owningInstitutionList    the owning institution list
     * @param owningInstBibIdList      the owning inst bib id list
     */
    public void prepareReportForUnMatchingTitles(Map<String, String> titleMap, List<Integer> bibIds, List<String> materialTypes, List<String> owningInstitutions, List<String> owningInstBibIds,
                                                 Set<String> unMatchingTitleHeaderSet, List<ReportDataEntity> reportDataEntityList, List<String> bibIdList,
                                                 List<String> materialTypeList, List<String> owningInstitutionList, List<String> owningInstBibIdList) {
        for (Iterator<String> stringIterator = unMatchingTitleHeaderSet.iterator(); stringIterator.hasNext(); ) {
            String titleHeader = stringIterator.next();
            int i = Integer.parseInt(titleHeader.replace(ScsbCommonConstants.TITLE, ""));
            if (bibIds != null) {
                bibIdList.add(String.valueOf(bibIds.get(i - 1)));
            }
            if (materialTypes != null) {
                materialTypeList.add(materialTypes.get(i - 1));
            }
            if (owningInstitutions != null) {
                owningInstitutionList.add(owningInstitutions.get(i - 1));
            }
            if (owningInstBibIds != null) {
                owningInstBibIdList.add(owningInstBibIds.get(i - 1));
            }
            ReportDataEntity titleReportDataEntity = new ReportDataEntity();
            titleReportDataEntity.setHeaderName(titleHeader);
            titleReportDataEntity.setHeaderValue(titleMap.get(titleHeader));
            reportDataEntityList.add(titleReportDataEntity);
        }
    }

    /**
     * This method gets matching match points based on the field name.
     *
     * @param fieldName the field name
     * @return the matching match points entity
     * @throws Exception the exception
     */
    public List<MatchingMatchPointsEntity> getMatchingMatchPointsEntity(String fieldName) throws Exception {
        List<MatchingMatchPointsEntity> matchingMatchPointsEntities = new ArrayList<>();
        String query = ScsbCommonConstants.DOCTYPE + ":" + ScsbCommonConstants.BIB +
                and + ScsbConstants.BIB_CATALOGING_STATUS + ":" + ScsbCommonConstants.COMPLETE_STATUS +
                and + ScsbCommonConstants.IS_DELETED_BIB + ":" + ScsbConstants.FALSE +
                and + coreParentFilterQuery + ScsbConstants.ITEM_CATALOGING_STATUS + ":" + ScsbCommonConstants.COMPLETE_STATUS +
                and + coreParentFilterQuery + ScsbCommonConstants.IS_DELETED_ITEM + ":" + ScsbConstants.FALSE;
        SolrQuery solrQuery = new SolrQuery(query);
        solrQuery.setFacet(true);
        solrQuery.addFacetField(fieldName);
        solrQuery.setFacetLimit(-1);
        solrQuery.setFacetMinCount(2);
        solrQuery.setRows(0);
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        QueryResponse queryResponse = solrTemplate.getSolrClient().query(solrQuery);
        stopWatch.stop();
        logger.info("Total Time Taken to get {} duplicates from solr : {}  ", fieldName, stopWatch.getTotalTimeSeconds());
        List<FacetField> facetFields = queryResponse.getFacetFields();
        for (FacetField facetField : facetFields) {
            List<FacetField.Count> values = facetField.getValues();
            for (Iterator<FacetField.Count> iterator = values.iterator(); iterator.hasNext(); ) {
                FacetField.Count next = iterator.next();
                String name = next.getName();
                if (StringUtils.isNotBlank(name)) {
                    MatchingMatchPointsEntity matchingMatchPointsEntity = new MatchingMatchPointsEntity();
                    matchingMatchPointsEntity.setMatchCriteria(fieldName);
                    matchingMatchPointsEntity.setCriteriaValue(name);
                    matchingMatchPointsEntity.setCriteriaValueCount((int) next.getCount());
                    matchingMatchPointsEntities.add(matchingMatchPointsEntity);
                }
            }
        }
        return matchingMatchPointsEntities;
    }

    /**
     * This method saves matching match point entities by using activemq.
     *
     * @param matchingMatchPointsEntities the matching match points entities
     */
    public void saveMatchingMatchPointEntities(List<MatchingMatchPointsEntity> matchingMatchPointsEntities) {
        int batchSize = 1000;
        int size = 0;
        if (CollectionUtils.isNotEmpty(matchingMatchPointsEntities)) {
            for (int i = 0; i < matchingMatchPointsEntities.size(); i += batchSize) {
                List<MatchingMatchPointsEntity> matchingMatchPointsEntityList = new ArrayList<>(matchingMatchPointsEntities.subList(i, Math.min(i + batchSize, matchingMatchPointsEntities.size())));
                producerTemplate.sendBody("scsbactivemq:queue:saveMatchingMatchPointsQ", matchingMatchPointsEntityList);
                size = size + matchingMatchPointsEntityList.size();
            }
        }
    }

    /**
     * This method gets cgd count based on institution from solr.
     *
     * @param owningInstitution the owning institution
     * @param cgd               the cgd
     * @return the cgd count based on inst
     * @throws SolrServerException the solr server exception
     * @throws IOException         the io exception
     */
    public Integer getCGDCountBasedOnInst(String owningInstitution, String cgd) throws SolrServerException, IOException {
        SolrQuery solrQuery = solrQueryBuilder.buildSolrQueryForCGDReports(owningInstitution, cgd);
        solrQuery.setRows(0);
        QueryResponse queryResponse = solrTemplate.getSolrClient().query(solrQuery);
        SolrDocumentList results = queryResponse.getResults();
        return Math.toIntExact(results.getNumFound());
    }

    /**
     * This method updates the reports which was found as an exception due to the different material types.
     *
     * @param exceptionRecordNums the exception record nums
     * @param batchSize           the batch size
     */
    public void updateExceptionRecords(List<Integer> exceptionRecordNums, Integer batchSize) {
        if (CollectionUtils.isNotEmpty(exceptionRecordNums)) {
            List<List<Integer>> exceptionRecordNumbers = Lists.partition(exceptionRecordNums, batchSize);
            for (List<Integer> exceptionRecordNumberList : exceptionRecordNumbers) {
                List<ReportEntity> reportEntities = reportDetailRepository.findByIdIn(exceptionRecordNumberList);
                for (ReportEntity reportEntity : reportEntities) {
                    reportEntity.setType(ScsbConstants.MATERIAL_TYPE_EXCEPTION);
                }
                reportDetailRepository.saveAll(reportEntities);
            }
        }
    }

    /**
     * This method updates reports which are found to be a monographic set record in database.
     *
     * @param nonMonographRecordNums the non monograph record nums
     * @param batchSize              the batch size
     */
    public void updateMonographicSetRecords(List<Integer> nonMonographRecordNums, Integer batchSize) {
        if (CollectionUtils.isNotEmpty(nonMonographRecordNums)) {
            List<List<Integer>> monographicSetRecordNumbers = Lists.partition(nonMonographRecordNums, batchSize);
            for (List<Integer> monographicSetRecordNumberList : monographicSetRecordNumbers) {
                List<ReportDataEntity> reportDataEntitiesToUpdate = reportDataDetailsRepository.getReportDataEntityByRecordNumIn(monographicSetRecordNumberList, ScsbConstants.MATERIAL_TYPE);
                if (CollectionUtils.isNotEmpty(reportDataEntitiesToUpdate)) {
                    for (ReportDataEntity reportDataEntity : reportDataEntitiesToUpdate) {
                        String headerValue = reportDataEntity.getHeaderValue();
                        String[] materialTypes = headerValue.split(",");
                        List<String> modifiedMaterialTypes = new ArrayList<>();
                        for (int i = 0; i < materialTypes.length; i++) {
                            modifiedMaterialTypes.add(ScsbConstants.MONOGRAPHIC_SET);
                        }
                        reportDataEntity.setHeaderValue(StringUtils.join(modifiedMaterialTypes, ","));
                    }
                    reportDataDetailsRepository.saveAll(reportDataEntitiesToUpdate);
                }
            }
        }
    }

    /**
     * This method saves the summary report for the counts of the CGD in each institutions.
     *
     * @param type the type
     */
    public void saveCGDUpdatedSummaryReport(String type) {
        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setType(type);
        reportEntity.setFileName(ScsbConstants.SUMMARY_REPORT_FILE_NAME);
        reportEntity.setCreatedDate(new Date());
        reportEntity.setInstitutionName(ScsbCommonConstants.ALL_INST);
        List<ReportDataEntity> reportDataEntities = new ArrayList<>();
        List<String> allInstitutionCodesExceptSupportInstitution = commonUtil.findAllInstitutionCodesExceptSupportInstitution();
        for (String institutionCode : allInstitutionCodesExceptSupportInstitution) {
            logger.info("{} Final Counter Value:{} ", institutionCode, MatchingCounter.getSpecificInstitutionCounterMap(institutionCode).get(MATCHING_COUNTER_SHARED));
            getReportDataEntity(institutionCode + "SharedCount", String.valueOf(MatchingCounter.getSpecificInstitutionCounterMap(institutionCode).get(MATCHING_COUNTER_UPDATED_SHARED)), reportDataEntities);
            getReportDataEntity(institutionCode + "OpenCount", String.valueOf(MatchingCounter.getSpecificInstitutionCounterMap(institutionCode).get(MATCHING_COUNTER_UPDATED_OPEN)), reportDataEntities);
        }
        reportEntity.addAll(reportDataEntities);
        getReportDetailRepository().save(reportEntity);
    }

    /**
     * This method populates matching counter for Ongoing Matching Algorithm to process the CGD update in the matching algorithm.
     *
     * @throws IOException         the io exception
     * @throws SolrServerException the solr server exception
     */
    public void populateMatchingCounter() throws IOException, SolrServerException {
        List<String> institutions = commonUtil.findAllInstitutionCodesExceptSupportInstitution();
        MatchingCounter.setScsbInstitutions(institutions);
        MatchingCounter.reset();
        for (String institution : institutions) {
            synchronized (MatchingCounter.class) {
                Map<String, Integer> specificInstitutionCounterMap = MatchingCounter.getSpecificInstitutionCounterMap(institution);
                specificInstitutionCounterMap.put(MATCHING_COUNTER_SHARED, getCGDCountBasedOnInst(institution, ScsbConstants.SHARED));
                specificInstitutionCounterMap.put(MATCHING_COUNTER_OPEN, getCGDCountBasedOnInst(institution, ScsbConstants.OPEN));
                logger.info("{} Initial Counter Value: {}", institution, specificInstitutionCounterMap.get(MATCHING_COUNTER_SHARED));
                MatchingCounter.setSpecificInstitutionCounterMap(institution, specificInstitutionCounterMap);
            }
        }
    }

    public void processBibIdGroupingMap(Set<Integer> bibIdSet, Map<Integer, UUID> identityMap, Map<UUID, Set<Integer>> map) {
        AtomicBoolean flag = new AtomicBoolean(false);
        try {
            if (map.size() == 0) {
                UUID uuid = UUID.randomUUID();
                map.put(uuid, bibIdSet);
                for (Integer bibId : bibIdSet) {
                    identityMap.put(bibId, uuid);
                }
            } else {
                Set<Integer> tempSet = new HashSet<>();
                bibIdSet.stream().forEach(bibId -> {
                    UUID identity = identityMap.get(bibId);
                    if (identity != null && map.get(identity) != null) {
                        tempSet.addAll(map.get(identity));
                        map.remove(identity);
                        flag.set(true);
                    }
                });

                UUID uuid = UUID.randomUUID();
                Set<Integer> set = new HashSet<>();
                set.addAll(bibIdSet);
                set.addAll(tempSet);
                map.put(uuid, set);
                set.stream().forEach(num -> identityMap.put(num, uuid));

                if (!flag.get()) {
                    UUID uuidNew = UUID.randomUUID();
                    map.put(uuidNew, bibIdSet);
                    for (Integer bibId : bibIdSet) {
                        identityMap.put(bibId, uuidNew);
                    }
                }
            }
        } catch (Exception e) {
            logger.info("Exception occured while processing bib Id grouping map - {} ", e.getMessage());
        }
    }

    public Map<UUID, LinkedHashSet<Integer>> processFinalIdentityGroupingMap(Map<Integer, UUID> identityMap) {
        Map<UUID, LinkedHashSet<Integer>> finalIdentityGroupingMap = null;
        try {
            finalIdentityGroupingMap = identityMap.entrySet().stream()
                    .collect(Collectors.groupingBy(Map.Entry::getValue)).values().stream()
                    .collect(Collectors.toMap(
                            item -> item.get(0).getValue(),
                            item -> new LinkedHashSet<>(
                                    item.stream()
                                            .map(Map.Entry::getKey)
                                            .collect(Collectors.toSet())
                            ))
                    );

        } catch (Exception e) {
            logger.info("Exception occured while processing final identity grouping map - {} ", e.getMessage());
        }
        return finalIdentityGroupingMap;
    }

    public void updateMatchingIdentityInDb(Map<UUID, LinkedHashSet<Integer>> collect) {
        StringBuilder finalBibIdIdentifierQueryString = new StringBuilder();
        StringBuilder bibIds = new StringBuilder();
        StopWatch stopWatch = new StopWatch();
        StopWatch stopWatchUpdateDb = new StopWatch();
        try {
            stopWatch.start();
            collect.entrySet().stream().forEach(e -> {
                e.getValue().stream().forEach(bibId -> {
                    finalBibIdIdentifierQueryString.append("WHEN " + bibId + " THEN '" + e.getKey().toString() + "'").append(" ");
                    bibIds.append(bibId).append(",");
                });
            });

            String query = "UPDATE bibliographic_t " +
                    "SET MATCHING_IDENTITY = ( CASE BIBLIOGRAPHIC_ID " + finalBibIdIdentifierQueryString + " END) " +
                    "WHERE BIBLIOGRAPHIC_ID IN (" + bibIds.substring(0, bibIds.length() - 1) + ")";

            stopWatch.stop();
            logger.info("Time taken to build query string Matching Identity :  {} seconds ",stopWatch.getTotalTimeSeconds());
            stopWatchUpdateDb.start();
            jdbcTemplate.batchUpdate(query);
            stopWatchUpdateDb.stop();
            logger.info("Time taken to update Matching Identity In Db :  {} seconds ",stopWatchUpdateDb.getTotalTimeSeconds());
        } catch (Exception e) {
            logger.info("Exception occured while processing final identity grouping map - {} ", e.getMessage());
        }
    }
}
