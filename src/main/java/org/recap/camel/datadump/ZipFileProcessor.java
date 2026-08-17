package org.recap.camel.datadump;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.Route;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.aws.s3.S3Constants;
import org.apache.camel.processor.aggregate.zipfile.ZipAggregationStrategy;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.recap.PropertyKeyConstants;
import org.recap.ScsbCommonConstants;
import org.recap.ScsbConstants;
import org.recap.util.datadump.DataExportHeaderUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.StringTokenizer;

/**
 * Created by peris on 11/6/16.
 */
@Slf4j
@Component
public class ZipFileProcessor implements Processor {



    /**
     * The s3 data dump remote server.
     */
    @Value("${" + PropertyKeyConstants.S3_DATA_DUMP_DIR + "}")
    String s3DataDumpRemoteServer;
    @Value("${" + PropertyKeyConstants.ETL_DATA_DUMP_FTP_STAGING_DIRECTORY + "}")
    private String s3StagingDir;

    /**
     * The Data export email processor.
     */
    @Autowired
    DataExportEmailProcessor dataExportEmailProcessor;


    ProducerTemplate producer;
    Exchange exchange;

    public ZipFileProcessor() {

    }

    public ZipFileProcessor(ProducerTemplate producer, Exchange exchange) {
        this.producer = producer;
        this.exchange = exchange;
    }

    /**
     * This method is invoked by route to zip the data dump files.
     *
     * @param exchange
     * @throws Exception
     */
    @Override
    public void process(Exchange exchange) throws Exception {
        String batchHeaders = (String) exchange.getIn().getHeader("batchHeaders");
        String folderName = getValueFor(batchHeaders, "folderName");
        dataExportEmailProcessor.setInstitutionCodes(getTokenizedCodes(getValueFor(batchHeaders, "institutionCodes")));
        dataExportEmailProcessor.setTransmissionType(getValueFor(batchHeaders, "transmissionType"));
        dataExportEmailProcessor.setFolderName(folderName);
        dataExportEmailProcessor.setRequestingInstitutionCode(getValueFor(batchHeaders, "requestingInstitutionCode"));
        dataExportEmailProcessor.setToEmailId(getValueFor(batchHeaders, "toEmailId"));
        dataExportEmailProcessor.setRequestId(getValueFor(batchHeaders, "requestId"));
        dataExportEmailProcessor.setFetchType(getValueFor(batchHeaders, "fetchType"));
        dataExportEmailProcessor.setImsDepositoryCodes(getTokenizedCodes(getValueFor(batchHeaders, "imsDepositoryCodes")));
        boolean isRequestFromSwagger = Boolean.parseBoolean(getValueFor(batchHeaders, "isRequestFromSwagger"));
        dataExportEmailProcessor.setRequestFromSwagger(isRequestFromSwagger);
        if(isRequestFromSwagger){
             dataExportEmailProcessor.setEltRequestId(Integer.valueOf(getValueFor(batchHeaders, "etlRequestId")));
        }

        Route ftpRoute = exchange.getContext().getRoute(ScsbConstants.FTP_ROUTE);
        if (null != ftpRoute) {
            exchange.getContext().removeRoute(ScsbConstants.FTP_ROUTE);
            log.info(ScsbConstants.FTP_ROUTE + " Removed");
        }

        exchange.getContext().addRoutes(new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                onCompletion()
                        .choice()
                        .when(exchangeProperty(ScsbCommonConstants.CAMEL_BATCH_COMPLETE))
                        .log("Sending Email After S3 Zipping")
                        .process(dataExportEmailProcessor)
                        .log("Data dump zipping completed.")
                        .bean(new ZipFileProcessor(exchange.getContext().createProducerTemplate(), exchange), "ftpOnCompletion")
                        .end();
                from("file:" + s3StagingDir + File.separator + folderName + "?noop=true&antInclude=*.xml,*.json")
                        .routeId(ScsbConstants.FTP_ROUTE)
                        .aggregate(new ZipAggregationStrategy(true, true))
                        .constant(true)
                        .completionFromBatchConsumer()
                        .eagerCheckCompletion()
                        .setHeader(S3Constants.KEY, simple(s3DataDumpRemoteServer+ folderName + ".zip"))
                        .to(ScsbConstants.SCSB_CAMEL_S3_TO_ENDPOINT);
            }
        });
    }

    private static String getValueFor(String batchHeaderString, String key) {
        return new DataExportHeaderUtil().getValueFor(batchHeaderString, key);
    }

    private static List<String> getTokenizedCodes(String institutionCodes) {
        List<String> codes = new ArrayList<>();
        StringTokenizer stringTokenizer = new StringTokenizer(institutionCodes, "*");
        while (stringTokenizer.hasMoreTokens()) {
            codes.add(stringTokenizer.nextToken());
        }
        return codes;
    }

    public void ftpOnCompletion() throws JSONException {
        log.info("FTP OnCompletionProcessor");
        String batchHeaders = (String) exchange.getIn().getHeader("batchHeaders");
        String reqestingInst = getValueFor(batchHeaders, "requestingInstitutionCode");
        log.info("Req Inst -> {}" , reqestingInst);
        if (ScsbConstants.EXPORT_SCHEDULER_CALL) {
            producer.sendBody(ScsbConstants.DATA_DUMP_COMPLETION_FROM, reqestingInst);
        }
        String dataDumpTypeCompletionMessage = getDataDumpTypeCompletionMessage(batchHeaders);
        producer.sendBody(ScsbConstants.DATA_DUMP_COMPLETION_TOPIC,buildJsonResponseForTopics(batchHeaders,reqestingInst,dataDumpTypeCompletionMessage));
    }


    private static JSONObject buildJsonResponseForTopics(String batchHeaders, String requestingInstitutionCode, String dataDumpTypeCompletionMessage) throws JSONException {
        JSONObject jsonObject = new JSONObject();
        String[] messageSplit = dataDumpTypeCompletionMessage.split("-");
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
        String fileNameWithPath = getValueFor(batchHeaders, ScsbConstants.FILENAME);
        String fileName = "DeletedDataDump".equalsIgnoreCase(messageSplit[0]) ? fileNameWithPath.split("/")[2].concat(ScsbConstants.ZIP_FILE_FORMAT) : fileNameWithPath.split("/")[3].concat(ScsbConstants.ZIP_FILE_FORMAT);
        jsonObject.put(ScsbCommonConstants.INSTITUTION,requestingInstitutionCode);
        jsonObject.put(ScsbConstants.FILENAME,fileName);
        jsonObject.put(ScsbConstants.EXPORTED_DATE,simpleDateFormat.format(new Date()));
        jsonObject.put(ScsbConstants.DATA_DUMP_TYPE,messageSplit[0]);
        jsonObject.put(ScsbConstants.MESSAGE,messageSplit[1]);
        return jsonObject;
    }

    private static String getDataDumpTypeCompletionMessage(String batchHeaders) {
        Integer fetchType = Integer.valueOf(getValueFor(batchHeaders, ScsbConstants.FETCH_TYPE));
        if (fetchType == 1){
            return "IncrementalDataDump-"+ ScsbConstants.DATA_DUMP_COMPLETION_TOPIC_MESSAGE;
        }else if (fetchType == 2){
            return "DeletedDataDump-"+ ScsbConstants.DELETED_DATA_DUMP_COMPLETION_TOPIC_MESSAGE;
        }else {
            return "FullDataDump-"+ ScsbConstants.FULL_DATA_DUMP_COMPLETION_TOPIC_MESSAGE;
        }
    }
}
