package org.recap.matchingalgorithm.service;

import com.google.common.collect.Lists;
import org.apache.camel.ProducerTemplate;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.SolrServerException;
import org.recap.ScsbCommonConstants;
import org.recap.ScsbConstants;
import org.recap.executors.SaveMatchingBibsCallable;
import org.recap.model.jpa.*;
import org.recap.repository.jpa.InstitutionDetailsRepository;
import org.recap.repository.jpa.MatchingBibDetailsRepository;
import org.recap.repository.jpa.MatchingMatchPointsDetailsRepository;
import org.recap.repository.jpa.ReportDataDetailsRepository;
import org.recap.service.ActiveMqQueuesInfo;
import org.recap.util.CommonUtil;
import org.recap.util.MatchingAlgorithmUtil;
import org.recap.util.SolrQueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.solr.core.SolrTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;

import javax.annotation.Resource;
import java.io.IOException;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;


/**
 * Created by angelind on 11/7/16.
 */
@Service
public class MatchingAlgorithmHelperService {

    private static final Logger logger = LoggerFactory.getLogger(MatchingAlgorithmHelperService.class);
    @Autowired
    InstitutionDetailsRepository institutionDetailsRepository;
    @Autowired
    ReportDataDetailsRepository reportDataDetailsRepository;
    @Autowired
    private MatchingBibDetailsRepository matchingBibDetailsRepository;
    @Autowired
    private MatchingMatchPointsDetailsRepository matchingMatchPointsDetailsRepository;
    @Autowired
    private MatchingAlgorithmUtil matchingAlgorithmUtil;
    @Autowired
    private SolrQueryBuilder solrQueryBuilder;
    @Resource(name = "recapSolrTemplate")
    private SolrTemplate solrTemplate;
    @Autowired
    private ProducerTemplate producerTemplate;
    private ExecutorService executorService;
    @Autowired
    private ActiveMqQueuesInfo activeMqQueuesInfo;
    @Autowired
    private CommonUtil commonUtil;
    @Autowired
    NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * Gets logger.
     *
     * @return the logger
     */
    public Logger getLogger() {
        return logger;
    }

    /**
     * Gets matching bib details repository.
     *
     * @return the matching bib details repository
     */
    public MatchingBibDetailsRepository getMatchingBibDetailsRepository() {
        return matchingBibDetailsRepository;
    }

    /**
     * Gets matching match points details repository.
     *
     * @return the matching match points details repository
     */
    public MatchingMatchPointsDetailsRepository getMatchingMatchPointsDetailsRepository() {
        return matchingMatchPointsDetailsRepository;
    }

    /**
     * Gets matching algorithm util.
     *
     * @return the matching algorithm util
     */
    public MatchingAlgorithmUtil getMatchingAlgorithmUtil() {
        return matchingAlgorithmUtil;
    }

    /**
     * Gets solr query builder.
     *
     * @return the solr query builder
     */
    public SolrQueryBuilder getSolrQueryBuilder() {
        return solrQueryBuilder;
    }

    /**
     * Gets solr template.
     *
     * @return the solr template
     */
    public SolrTemplate getSolrTemplate() {
        return solrTemplate;
    }

    /**
     * Gets producer template.
     *
     * @return the producer template
     */
    public ProducerTemplate getProducerTemplate() {
        return producerTemplate;
    }

    public ActiveMqQueuesInfo getActiveMqQueuesInfo() {
        return activeMqQueuesInfo;
    }

    /**
     * This method finds the matching records based on the match point field(OCLC,ISBN,ISSN,LCCN).
     *
     * @return the long
     * @throws Exception the exception
     */
    public long findMatchingAndPopulateMatchPointsEntities() {
        List<String> matchingMatchPoints = ScsbConstants.MATCHING_MATCH_POINTS;
        long count = matchingMatchPoints
                .stream()
                .mapToLong(this::loadAndSaveMatchingMatchPointEntities)
                .sum();
        logger.info("Total count in MatchPoints : {} ", count);
        drainAllQueueMsgs("saveMatchingMatchPointsQ");

        return count;
    }

    private long loadAndSaveMatchingMatchPointEntities(String matchPointFieldOclc) {
        List<MatchingMatchPointsEntity> matchingMatchPointsEntities = null;
        try {
            matchingMatchPointsEntities = getMatchingAlgorithmUtil().getMatchingMatchPointsEntity(matchPointFieldOclc);
            getMatchingAlgorithmUtil().saveMatchingMatchPointEntities(matchingMatchPointsEntities);
        } catch (Exception exception) {
            logger.info("Exception in finding MatchPoints : {}", exception.getMessage());
        }
        return matchingMatchPointsEntities.size();
    }

    /**
     * This method is used to populate matching bib records in the database.
     *
     * @return the long
     * @throws IOException         the io exception
     * @throws SolrServerException the solr server exception
     */
    public long populateMatchingBibEntities() {
        long count = ScsbConstants.MATCHING_MATCH_POINTS.stream().mapToLong(this::fetchAndSaveMatchingBibs).sum();
        drainAllQueueMsgs("saveMatchingBibsQ");
        return count;
    }

    private void drainAllQueueMsgs(String queueName) {
        Integer saveMatchingBibsQ = getActiveMqQueuesInfo().getActivemqQueuesInfo(queueName);
        if (saveMatchingBibsQ != null) {
            while (saveMatchingBibsQ != 0) {
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    logger.error(ScsbConstants.ERROR, e);
                }
                saveMatchingBibsQ = getActiveMqQueuesInfo().getActivemqQueuesInfo(queueName);
            }
        }
    }

    public Map<String, Integer> populateReportsForMatchPoints(Integer batchSize, String matchPoint1, String matchPoint2, Map<String, Integer> institutionCounterMap) {

        List<Integer> multiMatchBibIdsForMatchPoint1AndMatchPoint2 = null;
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        if (matchPoint1.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_OCLC) && matchPoint2.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_ISBN)) {
            multiMatchBibIdsForMatchPoint1AndMatchPoint2 = getMatchingBibDetailsRepository().getMultiMatchBibIdsForOclcAndIsbn();
        } else if (matchPoint1.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_OCLC) && matchPoint2.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_ISSN)) {
            multiMatchBibIdsForMatchPoint1AndMatchPoint2 = getMatchingBibDetailsRepository().getMultiMatchBibIdsForOclcAndIssn();
        } else if (matchPoint1.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_OCLC) && matchPoint2.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_LCCN)) {
            multiMatchBibIdsForMatchPoint1AndMatchPoint2 = getMatchingBibDetailsRepository().getMultiMatchBibIdsForOclcAndLccn();
        } else if (matchPoint1.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_ISBN) && matchPoint2.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_ISSN)) {
            multiMatchBibIdsForMatchPoint1AndMatchPoint2 = getMatchingBibDetailsRepository().getMultiMatchBibIdsForIsbnAndIssn();
        } else if (matchPoint1.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_ISBN) && matchPoint2.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_LCCN)) {
            multiMatchBibIdsForMatchPoint1AndMatchPoint2 = getMatchingBibDetailsRepository().getMultiMatchBibIdsForIsbnAndLccn();
        } else if (matchPoint1.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_ISSN) && matchPoint2.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_LCCN)) {
            multiMatchBibIdsForMatchPoint1AndMatchPoint2 = getMatchingBibDetailsRepository().getMultiMatchBibIdsForIssnAndLccn();
        }
        List<List<Integer>> multipleMatchBibIds = Lists.partition(multiMatchBibIdsForMatchPoint1AndMatchPoint2, batchSize);
        Map<String, Set<Integer>> matchPoint1AndBibIdMap = new HashMap<>();
        Map<Integer, MatchingBibEntity> bibEntityMap = new HashMap<>();
        buildBibIdAndBibEntityMap(multipleMatchBibIds, matchPoint1AndBibIdMap, bibEntityMap, getLogger(), matchPoint1, matchPoint2);
        Set<String> matchPoint1Set = new HashSet<>();
        for (Iterator<String> iterator = matchPoint1AndBibIdMap.keySet().iterator(); iterator.hasNext(); ) {
            String matchPoint = iterator.next();
            if (!matchPoint1Set.contains(matchPoint)) {
                StringBuilder matchPoints1 = new StringBuilder();
                StringBuilder matchPoints2 = new StringBuilder();
                matchPoint1Set.add(matchPoint);
                Set<Integer> bibIds = matchPoint1AndBibIdMap.get(matchPoint);
                Set<Integer> tempBibIds = new HashSet<>(bibIds);
                for (Integer bibId : bibIds) {
                    MatchingBibEntity matchingBibEntity = bibEntityMap.get(bibId);
                    if (matchPoint1.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_OCLC) && matchPoint2.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_ISBN)) {
                        matchPoints1.append(StringUtils.isNotBlank(matchPoints1.toString()) ? "," : "").append(matchingBibEntity.getOclc());
                        matchPoints2.append(StringUtils.isNotBlank(matchPoints2.toString()) ? "," : "").append(matchingBibEntity.getIsbn());
                    } else if (matchPoint1.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_OCLC) && matchPoint2.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_ISSN)) {
                        matchPoints1.append(StringUtils.isNotBlank(matchPoints1.toString()) ? "," : "").append(matchingBibEntity.getOclc());
                        matchPoints2.append(StringUtils.isNotBlank(matchPoints2.toString()) ? "," : "").append(matchingBibEntity.getIssn());
                    } else if (matchPoint1.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_OCLC) && matchPoint2.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_LCCN)) {
                        matchPoints1.append(StringUtils.isNotBlank(matchPoints1.toString()) ? "," : "").append(matchingBibEntity.getOclc());
                        matchPoints2.append(StringUtils.isNotBlank(matchPoints2.toString()) ? "," : "").append(matchingBibEntity.getLccn());
                    } else if (matchPoint1.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_ISBN) && matchPoint2.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_ISSN)) {
                        matchPoints1.append(StringUtils.isNotBlank(matchPoints1.toString()) ? "," : "").append(matchingBibEntity.getIsbn());
                        matchPoints2.append(StringUtils.isNotBlank(matchPoints2.toString()) ? "," : "").append(matchingBibEntity.getIssn());
                    } else if (matchPoint1.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_ISBN) && matchPoint2.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_LCCN)) {
                        matchPoints1.append(StringUtils.isNotBlank(matchPoints2.toString()) ? "," : "").append(matchingBibEntity.getIsbn());
                        matchPoints2.append(StringUtils.isNotBlank(matchPoints2.toString()) ? "," : "").append(matchingBibEntity.getLccn());
                    } else if (matchPoint1.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_ISSN) && matchPoint2.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_LCCN)) {
                        matchPoints1.append(StringUtils.isNotBlank(matchPoints2.toString()) ? "," : "").append(matchingBibEntity.getIssn());
                        matchPoints2.append(StringUtils.isNotBlank(matchPoints2.toString()) ? "," : "").append(matchingBibEntity.getLccn());
                    }
                    String[] matchPoint1List = matchPoints1.toString().split(",");
                    tempBibIds.addAll(getMatchingAlgorithmUtil().getBibIdsForCriteriaValue(matchPoint1AndBibIdMap, matchPoint1Set, matchPoint, matchPoint1, matchPoint1List, bibEntityMap, matchPoints1));
                }
                if (matchPoint1.equalsIgnoreCase(ScsbCommonConstants.MATCH_POINT_FIELD_OCLC)) {
                    getMatchingAlgorithmUtil().populateAndSaveReportEntity(tempBibIds, bibEntityMap, ScsbCommonConstants.OCLC_CRITERIA, matchPoint2, matchPoints1.toString(), matchPoints2.toString(), institutionCounterMap);
                } else {
                    getMatchingAlgorithmUtil().populateAndSaveReportEntity(tempBibIds, bibEntityMap, matchPoint1, matchPoint2, matchPoints1.toString(), matchPoints2.toString(), institutionCounterMap);
                }
            }
        }
        stopWatch.stop();
        getLogger().info("Time taken to save - {} and {} Combination Reports : {}", matchPoint1, matchPoint2, stopWatch.getTotalTimeSeconds());
        return institutionCounterMap;
    }

    /**
     * This method is used to populate reports for single match.
     *
     * @param batchSize             the batch size
     * @param institutionCounterMap
     * @return the map
     */
    public Map<String, Integer> populateReportsForSingleMatch(Integer batchSize, Map<String, Integer> institutionCounterMap) {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        getMatchingAlgorithmUtil().getSingleMatchBibsAndSaveReport(batchSize, ScsbCommonConstants.MATCH_POINT_FIELD_OCLC, institutionCounterMap);
        getMatchingAlgorithmUtil().getSingleMatchBibsAndSaveReport(batchSize, ScsbCommonConstants.MATCH_POINT_FIELD_ISBN, institutionCounterMap);
        getMatchingAlgorithmUtil().getSingleMatchBibsAndSaveReport(batchSize, ScsbCommonConstants.MATCH_POINT_FIELD_ISSN, institutionCounterMap);
        getMatchingAlgorithmUtil().getSingleMatchBibsAndSaveReport(batchSize, ScsbCommonConstants.MATCH_POINT_FIELD_LCCN, institutionCounterMap);
        Integer saveMatchingBibsQ = getActiveMqQueuesInfo().getActivemqQueuesInfo("updateMatchingBibEntityQ");
        if (saveMatchingBibsQ != null) {
            while (saveMatchingBibsQ != 0) {
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    logger.error(ScsbConstants.ERROR, e);
                }
                saveMatchingBibsQ = getActiveMqQueuesInfo().getActivemqQueuesInfo("updateMatchingBibEntityQ");
            }
        }
        populateReportsForPendingMatches(batchSize, institutionCounterMap);
        stopWatch.stop();
        getLogger().info("Time taken to save Single Matching Reports : {}", stopWatch.getTotalTimeSeconds());
        return institutionCounterMap;
    }

    /**
     * Populate reports for pending matches map.
     *
     * @param batchSize             the batch size
     * @param institutionCounterMap
     * @return the map
     */
    public Map<String, Integer> populateReportsForPendingMatches(Integer batchSize, Map<String, Integer> institutionCounterMap) {

        Page<MatchingBibEntity> matchingBibEntities = getMatchingBibDetailsRepository().findByStatus(PageRequest.of(0, batchSize), ScsbConstants.PENDING);
        int totalPages = matchingBibEntities.getTotalPages();
        List<MatchingBibEntity> matchingBibEntityList = matchingBibEntities.getContent();
        Set<Integer> matchingBibIds = new HashSet<>();
        getMatchingAlgorithmUtil().processPendingMatchingBibs(matchingBibEntityList, matchingBibIds, institutionCounterMap);
        for (int pageNum = 1; pageNum < totalPages; pageNum++) {
            matchingBibEntities = getMatchingBibDetailsRepository().findByStatus(PageRequest.of(pageNum, batchSize), ScsbConstants.PENDING);
            matchingBibEntityList = matchingBibEntities.getContent();
            getMatchingAlgorithmUtil().processPendingMatchingBibs(matchingBibEntityList, matchingBibIds, institutionCounterMap);
        }

        getMatchingBibDetailsRepository().updateStatus(ScsbCommonConstants.COMPLETE_STATUS, ScsbConstants.PENDING);
        return institutionCounterMap;
    }

    /**
     * This method is used to save matching summary count.
     *
     * @param institutionCounterMap institutionsMatchingCount
     */
    public void saveMatchingSummaryCount(Map<String, Integer> institutionCounterMap) {
        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setType("MatchingCount");
        reportEntity.setCreatedDate(new Date());
        reportEntity.setFileName("MatchingSummaryCount");
        reportEntity.setInstitutionName(ScsbCommonConstants.LCCN_CRITERIA);
        List<ReportDataEntity> reportDataEntities = new ArrayList<>();


        List<String> allInstitutionCodesExceptSupportInstitution = commonUtil.findAllInstitutionCodesExceptSupportInstitution();
        for (String institution : allInstitutionCodesExceptSupportInstitution) {
            ReportDataEntity reportDataEntity = new ReportDataEntity();
            reportDataEntity.setHeaderName(institution.toLowerCase() + "MatchingCount");
            reportDataEntity.setHeaderValue(String.valueOf(institutionCounterMap.get(institution)));
            reportDataEntities.add(reportDataEntity);
        }
        reportEntity.addAll(reportDataEntities);
        getProducerTemplate().sendBody("scsbactivemq:queue:saveMatchingReportsQ", Collections.singletonList(reportEntity));
    }

    /**
     * This method is used to fetch and save matching bibs.
     *
     * @param matchCriteria the match criteria
     * @return the integer
     */
    public Integer fetchAndSaveMatchingBibs(String matchCriteria) {
        long batchSize = 300;
        Integer size = 0;
        try {
            long countBasedOnCriteria = getMatchingMatchPointsDetailsRepository().countBasedOnCriteria(matchCriteria);
            SaveMatchingBibsCallable saveMatchingBibsCallable = new SaveMatchingBibsCallable();
            SaveMatchingBibsCallable.setBibIdList(new HashSet<>());
            int totalPagesCount = (int) (countBasedOnCriteria / batchSize);
            ExecutorService executor = getExecutorService(50);
            List<Callable<Integer>> callables = new ArrayList<>();
            for (int pageNum = 0; pageNum < totalPagesCount + 1; pageNum++) {
                Callable callable = new SaveMatchingBibsCallable(getMatchingMatchPointsDetailsRepository(), matchCriteria, getSolrTemplate(),
                        getProducerTemplate(), getSolrQueryBuilder(), batchSize, pageNum, getMatchingAlgorithmUtil());
                callables.add(callable);
            }
            size = executeCallables(size, executor, callables);
        } catch (Exception exception) {
            logger.info("Exception caught in saving Matching Bibs : {}", exception.getMessage());
        }
        return size;
    }

    public Set<Integer> getBibIdSetFromString(ReportDataEntity reportDataEntity) {
        String bibId = reportDataEntity.getHeaderValue();
        String[] bibIds = bibId.split(",");
        Set<Integer> bibIdSet = new HashSet<>();
        for (int i = 0; i < bibIds.length; i++) {
            bibIdSet.add(Integer.valueOf(bibIds[i]));
        }
        return bibIdSet;
    }

    private Integer executeCallables(Integer size, ExecutorService executorService, List<Callable<Integer>> callables) {
        List<Future<Integer>> futures = null;
        try {
            futures = getFutures(executorService, callables);
        } catch (Exception e) {
            logger.error(ScsbCommonConstants.LOG_ERROR, e);
        }

        if (futures != null) {
            for (Iterator<Future<Integer>> iterator = futures.iterator(); iterator.hasNext(); ) {
                Future future = iterator.next();
                try {
                    size += (Integer) future.get();
                } catch (InterruptedException e) {
                    logger.error(ScsbCommonConstants.LOG_ERROR, e);
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    logger.error(ScsbCommonConstants.LOG_ERROR, e);
                }
            }
        }
        return size;
    }

    private List<Future<Integer>> getFutures(ExecutorService executorService, List<Callable<Integer>> callables) throws InterruptedException {
        List<Future<Integer>> futures = executorService.invokeAll(callables);
        List<Future<Integer>> collectedFutures = futures.stream().map(future -> {
            try {
                future.get();
                return future;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).collect(Collectors.toList());
        logger.info("No of Futures Collected : {}", collectedFutures.size());
        return collectedFutures;
    }

    private ExecutorService getExecutorService(Integer numThreads) {
        if (null == executorService || executorService.isShutdown()) {
            executorService = Executors.newFixedThreadPool(numThreads);
        }
        return executorService;
    }

    private void populateBibIds(Map<String, Set<Integer>> isbnAndBibIdMap, Map<Integer, MatchingBibEntity> bibEntityMap, List<List<Integer>> multipleMatchBibIds, String matchPoint) {
        buildBibIdAndBibEntityMap(multipleMatchBibIds, isbnAndBibIdMap, bibEntityMap, logger, ScsbCommonConstants.MATCH_POINT_FIELD_ISBN, matchPoint);
    }

    private void buildBibIdAndBibEntityMap(List<List<Integer>> multipleMatchBibIds, Map<String, Set<Integer>> matchPoint1AndBibIdMap, Map<Integer, MatchingBibEntity> bibEntityMap, Logger logger, String matchPoint1, String matchPoint2) {
        logger.info(ScsbConstants.TOTAL_BIB_ID_PARTITION_LIST, multipleMatchBibIds.size());
        for (List<Integer> bibIds : multipleMatchBibIds) {
            List<MatchingBibEntity> bibEntitiesBasedOnBibIds = getMatchingBibDetailsRepository().getMultiMatchBibEntitiesBasedOnBibIds(bibIds, matchPoint1, matchPoint2);
            if (CollectionUtils.isNotEmpty(bibEntitiesBasedOnBibIds)) {
                getMatchingAlgorithmUtil().populateBibIdWithMatchingCriteriaValue(matchPoint1AndBibIdMap, bibEntitiesBasedOnBibIds, matchPoint1, bibEntityMap);
            }
        }
    }

    public String groupBibsForMonograph(Integer batchSize, Boolean isPendingMatch) {
        long countOfRecordNum;
        if (isPendingMatch) {
            countOfRecordNum = reportDataDetailsRepository.getCountOfRecordNumForMatchingPendingMonograph(ScsbCommonConstants.BIB_ID);
        } else {
            countOfRecordNum = reportDataDetailsRepository.getCountOfRecordNumForMatchingMonograph(ScsbCommonConstants.BIB_ID);
        }
        logger.info(ScsbConstants.TOTAL_RECORDS + "{}", countOfRecordNum);
        int totalPagesCount = (int) (countOfRecordNum / batchSize);
        logger.info(ScsbConstants.TOTAL_PAGES + "{}", totalPagesCount);
        int pageNum = 0;
        logger.info("Batch count - {}", totalPagesCount);
        while (pageNum < totalPagesCount + 1) {
            logger.info("Executing the current Batch count for Monograph- {} - Batch count - {} -", pageNum, totalPagesCount);
            StopWatch stopWatch = new StopWatch();
            stopWatch.start();
            StopWatch stopWatchForReportDataQuery = new StopWatch();
            stopWatchForReportDataQuery.start();
            long from = pageNum * Long.valueOf(batchSize);
            List<ReportDataEntity> reportDataEntities;
            if (isPendingMatch) {
                reportDataEntities = reportDataDetailsRepository.getReportDataEntityForPendingMatchingMonographs(ScsbCommonConstants.BIB_ID, from, batchSize);
            } else {
                reportDataEntities = reportDataDetailsRepository.getReportDataEntityForMatchingMonographs(ScsbCommonConstants.BIB_ID, from, batchSize);
            }
            stopWatchForReportDataQuery.stop();
            logger.info("Time taken to get the report data entity from db :  {} seconds ",stopWatchForReportDataQuery.getTotalTimeSeconds());
            populateMatchingIdentifier(reportDataEntities);
            logger.info("Before clearing the reportDataEntities");
            reportDataEntities.clear();
            logger.info("After clearing the reportDataEntities");
            stopWatch.stop();
            logger.info("Time taken to process grouping in batches in 10K :  {} seconds ",stopWatch.getTotalTimeSeconds());
            pageNum++;
        }
        return "Success";
    }

    public void groupBibsForMVMS(Integer batchSize) {
        StopWatch stopWatch = new StopWatch();
        long countOfRecordNum = reportDataDetailsRepository.getCountOfRecordNumForMatchingMVMs(ScsbCommonConstants.BIB_ID);
        logger.info(ScsbConstants.TOTAL_RECORDS + "{}", countOfRecordNum);
        int totalPagesCount = (int) (countOfRecordNum / batchSize);
        logger.info(ScsbConstants.TOTAL_PAGES + "{}", totalPagesCount);
        int pageNum = 0;
        while (pageNum < totalPagesCount + 1) {
            logger.info("Executing the current Batch count for MVMs- {} - Batch count - {} -", pageNum, totalPagesCount);
            stopWatch.start();
            long from = pageNum * Long.valueOf(batchSize);
            List<ReportDataEntity> reportDataEntities = reportDataDetailsRepository.getReportDataEntityForMatchingMVMs(ScsbCommonConstants.BIB_ID, from, batchSize);
            populateMatchingIdentifier(reportDataEntities);
            reportDataEntities.clear();
            stopWatch.stop();
            logger.info("Time taken to process grouping in batches in 10K :  {} seconds ",stopWatch.getTotalTimeSeconds());
            pageNum++;
        }
    }

    public void groupBibsForSerials(Integer batchSize) {
        StopWatch stopWatch = new StopWatch();
        long countOfRecordNum = reportDataDetailsRepository.getCountOfRecordNumForMatchingSerials(ScsbCommonConstants.BIB_ID);
        logger.info(ScsbConstants.TOTAL_RECORDS + "{}", countOfRecordNum);
        int totalPagesCount = (int) (countOfRecordNum / batchSize);
        logger.info(ScsbConstants.TOTAL_PAGES + "{}", totalPagesCount);
        int pageNum = 0;
        while (pageNum < totalPagesCount + 1) {
            logger.info("Executing the current Batch count for Serials - {} - Batch count - {} -", pageNum, totalPagesCount);
            stopWatch.start();
            long from = pageNum * Long.valueOf(batchSize);
            List<ReportDataEntity> reportDataEntities = reportDataDetailsRepository.getReportDataEntityForMatchingSerials(ScsbCommonConstants.BIB_ID, from, batchSize);
            populateMatchingIdentifier(reportDataEntities);
            reportDataEntities.clear();
            stopWatch.stop();
            logger.info("Time taken to process grouping in batches in 10K :  {} seconds ",stopWatch.getTotalTimeSeconds());
            pageNum++;
        }
    }

    private void populateMatchingIdentifier(List<ReportDataEntity> reportDataEntities) {
        Map<String, Set<Integer>> bibIdMap = new LinkedHashMap<>();
        Map<Integer, String> identityMap = new LinkedHashMap<>();
        StopWatch stopWatch = new StopWatch();
        StopWatch stopWatchQuery = new StopWatch();
        try {
            logger.info("Into the populateMatchingIdentifier method");
            stopWatch.start();
            stopWatchQuery.start();
            Set<Set<Integer>> finalBibSetOfSets = reportDataEntities.parallelStream().map(this::getBibIdSetFromString).collect(Collectors.toSet());
            Set<Integer> bibIdsList = finalBibSetOfSets.parallelStream().flatMap(Collection::stream).collect(Collectors.toSet());
            Map<Integer, BibliographicEntity> bibIdAndBibEntityMap = matchingAlgorithmUtil.getBibIdAndBibEntityMap(bibIdsList);

            stopWatchQuery.stop();
            logger.info("Time taken to  build bibIdAndBibEntityMap:  {} seconds ",stopWatchQuery.getTotalTimeSeconds());

            finalBibSetOfSets.forEach(bibIdSet -> {
                matchingAlgorithmUtil.processBibIdGroupingMap(bibIdSet, identityMap, bibIdMap, bibIdAndBibEntityMap);
            });
            bibIdAndBibEntityMap.clear();
        } catch (Exception e) {
            logger.info("Exception occured - {}", e.getMessage());
        } finally {
            Map<String, LinkedHashSet<Integer>> finalIdentityGroupingMap = matchingAlgorithmUtil.processFinalIdentityGroupingMap(identityMap);
            matchingAlgorithmUtil.updateMatchingIdentityInDb(finalIdentityGroupingMap);
            stopWatch.stop();
            logger.info("Time taken to populate Matching Identifier :  {} seconds ",stopWatch.getTotalTimeSeconds());
        }
    }
}
