package org.recap.camel.datadump;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.commons.collections.CollectionUtils;
import org.recap.PropertyKeyConstants;
import org.recap.ScsbCommonConstants;
import org.recap.ScsbConstants;
import org.recap.model.csv.DataDumpFailureReport;
import org.recap.model.csv.DataDumpSuccessReport;
import org.recap.model.export.DataDumpRequest;
import org.recap.model.jparw.ETLRequestLogEntity;
import org.recap.model.jparw.ExportStatusEntity;
import org.recap.model.jparw.ReportDataEntity;
import org.recap.model.jparw.ReportEntity;
import org.recap.report.S3DataDumpFailureReportGenerator;
import org.recap.report.S3DataDumpSuccessReportGenerator;
import org.recap.repositoryrw.ETLRequestLogDetailsRepository;
import org.recap.repositoryrw.ExportStatusDetailsRepository;
import org.recap.repositoryrw.ReportDetailRepository;
import org.recap.service.email.datadump.DataDumpEmailService;
import org.recap.service.preprocessor.DataDumpExportService;
import org.recap.util.datadump.DataDumpUtil;
import org.recap.util.datadump.DataExportHeaderUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Created by peris on 11/5/16.
 */
@Slf4j
@Component
public class DataExportEmailProcessor implements Processor {

    /**
     * The Data dump email service.
     */
    @Autowired
    DataDumpEmailService dataDumpEmailService;

    @Autowired
    DataDumpExportService dataDumpExportService;

    /**
     * The Report detail repository.
     */
    @Autowired
    ReportDetailRepository reportDetailRepository;

    @Autowired
    DataDumpUtil dataDumpUtil;

    /**
     * The Data export header util.
     */
    @Autowired
    DataExportHeaderUtil dataExportHeaderUtil;

    /**
     * The Data dump status file name.
     */
    @Value("${" + PropertyKeyConstants.ETL_DATA_DUMP_STATUS_FILE_NAME + "}")
    String dataDumpStatusFileName;

    @Value("${" + PropertyKeyConstants.ETL_DATA_DUMP_FETCHTYPE_FULL + "}")
    private String fetchTypeFull;

    /**
     * The Ftp data dump success report generator.
     */
    @Autowired
    S3DataDumpSuccessReportGenerator s3DataDumpSuccessReportGenerator;

    /**
     * The Ftp data dump failure report generator.
     */
    @Autowired
    S3DataDumpFailureReportGenerator s3DataDumpFailureReportGenerator;

    /**
     * The Producer template.
     */
    @Autowired
    ProducerTemplate producerTemplate;

    @Autowired
    ETLRequestLogDetailsRepository etlRequestLogDetailsRepository;

    @Autowired
    ExportStatusDetailsRepository exportStatusDetailsRepository;

    private String transmissionType;
    private List<String> institutionCodes;
    private List<String> imsDepositoryCodes;
    private String requestingInstitutionCode;
    private String folderName;
    private String reportFileName;
    private String toEmailId;
    private String requestId;
    private String fetchType;
    private Integer eltRequestId;

    private boolean isRequestFromSwagger;

    /**
     * This method is invoked by route to send batch export report to FTP and send email to the configured email id.
     *
     * @param exchange
     * @throws Exception
     */
    @Override
    public void process(Exchange exchange) throws Exception {
        String totalRecordCount = "0";
        String failedBibs = "0";
        String exportedItemCount = "0";
        List<ReportEntity> byFileName = reportDetailRepository.findByFileName(requestId);
        setReportFileName(byFileName.get(0));
        List<ReportEntity> successReportEntities = new ArrayList<>();
        List<ReportEntity> failureReportEntities = new ArrayList<>();
        for (ReportEntity reportEntity:byFileName) {
            List<ReportDataEntity> reportDataEntities = reportEntity.getReportDataEntities();
            for (Iterator<ReportDataEntity> iterator = reportDataEntities.iterator(); iterator.hasNext(); ) {
                ReportDataEntity reportDataEntity = iterator.next();
                if(reportDataEntity.getHeaderName().equals(ScsbConstants.NUM_BIBS_EXPORTED)){
                    totalRecordCount = reportDataEntity.getHeaderValue();
                }
                if(reportDataEntity.getHeaderName().equals(ScsbConstants.FAILED_BIBS)){
                    failedBibs = reportDataEntity.getHeaderValue();
                }
                if(reportDataEntity.getHeaderName().equals(ScsbConstants.EXPORTED_ITEM_COUNT)){
                    exportedItemCount = reportDataEntity.getHeaderValue();
                }
            }
            if(reportEntity.getType().equalsIgnoreCase(ScsbConstants.BATCH_EXPORT_SUCCESS)) {
                successReportEntities.add(reportEntity);
            } else if(reportEntity.getType().equalsIgnoreCase(ScsbConstants.BATCH_EXPORT_FAILURE)) {
                failureReportEntities.add(reportEntity);
            }
        }
        sendBatchExportReportToFTP(successReportEntities, ScsbCommonConstants.SUCCESS);
        sendBatchExportReportToFTP(failureReportEntities, ScsbCommonConstants.FAILURE);

        if(fetchType.equals(fetchTypeFull)) {
            log.info("Sending email for full dump");
            processEmail(totalRecordCount,failedBibs,exportedItemCount,fetchType,requestingInstitutionCode);
        }
        else if(fetchType.equals(ScsbConstants.DATADUMP_FETCHTYPE_INCREMENTAL)){
            log.info("Sending email for incremental dump");
            processEmail(totalRecordCount,failedBibs,exportedItemCount,fetchType,requestingInstitutionCode);
        }
        else if(fetchType.equals(ScsbConstants.DATADUMP_FETCHTYPE_DELETED)){
            log.info("Sending email for deleted dump");
            processEmail(totalRecordCount,failedBibs,exportedItemCount,fetchType,requestingInstitutionCode);
        }
        updateDataDumpStatus();
    }

    private void setReportFileName(ReportEntity reportEntity){
        for (ReportDataEntity reportDataEntity:reportEntity.getReportDataEntities()){
            if(reportDataEntity.getHeaderName().equals(ScsbConstants.HEADER_FETCH_TYPE)){
                if (reportDataEntity.getHeaderValue().equals(ScsbConstants.DATADUMP_FETCHTYPE_INCREMENTAL)) {
                    String[] split = folderName.split("/");
                    reportFileName =split[0]+File.separator+split[1]+File.separator+split[2]+File.separator+ ScsbConstants.EXPORT_DATA_DUMP_INCREMENTAL+split[3];
                } else if(reportDataEntity.getHeaderValue().equals(ScsbConstants.DATADUMP_FETCHTYPE_DELETED)) {
                    String[] split = folderName.split("/");
                    reportFileName = split[0]+File.separator+split[1]+File.separator+ ScsbConstants.EXPORT_DATA_DUMP_DELETIONS+split[2];
                }
                else{
                    String[] split = folderName.split("/");
                    reportFileName =split[0]+File.separator+split[1]+File.separator+split[2]+File.separator+ ScsbConstants.EXPORT_DATA_DUMP_FULL+split[3];
                }
            }
        }
    }
    /**
     * To send a batch export success and failure reports to FTP.
     *
     * @param reportEntities
     * @param type
     */
    private void sendBatchExportReportToFTP(List<ReportEntity> reportEntities, String type) {
        if(CollectionUtils.isNotEmpty(reportEntities)) {
            if(type.equalsIgnoreCase(ScsbCommonConstants.SUCCESS)) {
                DataDumpSuccessReport dataDumpSuccessReport = s3DataDumpSuccessReportGenerator.getDataDumpSuccessReport(reportEntities, reportFileName);
                producerTemplate.sendBody(ScsbConstants.DATAEXPORT_WITH_SUCCESS_REPORT_FTP_Q, dataDumpSuccessReport);
                log.info("The Success Report folder : {}", folderName);
            } else if (type.equalsIgnoreCase(ScsbCommonConstants.FAILURE)) {
                DataDumpFailureReport dataDumpFailureReport = s3DataDumpFailureReportGenerator.getDataDumpFailureReport(reportEntities, reportFileName);
                producerTemplate.sendBody(ScsbConstants.DATAEXPORT_WITH_FAILURE_REPORT_FTP_Q, dataDumpFailureReport);
                log.info("The Failure Report folder : {}", folderName);
            }
        }
    }

    /**
     * This method writes the completed status to data dump status file to indicate its completed.
     *
     * @throws IOException
     */
    private void updateDataDumpStatus() throws IOException {
        log.info("Changing status to completed");
        //TODo change the status to complete based on etl request id
        if(isRequestFromSwagger){
            updateStatusInDB();
        }
        else{
            updateStatusInFile();
        }
    }

    private void updateStatusInFile() {
        File file = new File(dataDumpStatusFileName);
        try (FileWriter fileWriter = new FileWriter(file, false)) {
            fileWriter.append(ScsbConstants.COMPLETED);
            fileWriter.flush();
        } catch (IOException e) {
            log.error(ScsbConstants.EXCEPTION, e);
        }
    }

    private void updateStatusInDB() {
        Optional<ETLRequestLogEntity> inProgressExportLog = etlRequestLogDetailsRepository.findById(eltRequestId);
        if(inProgressExportLog.isPresent()){
            ETLRequestLogEntity inProgressRequestLogEntity = inProgressExportLog.get();
            ExportStatusEntity exportStatusEntity = exportStatusDetailsRepository.findByExportStatusCode(ScsbConstants.COMPLETED);
            inProgressRequestLogEntity.setExportStatusId(exportStatusEntity.getId());
            inProgressRequestLogEntity.setExportStatusEntity(exportStatusEntity);
            inProgressRequestLogEntity.setCompleteTime(new Date());
            etlRequestLogDetailsRepository.saveAndFlush(inProgressRequestLogEntity);
            DataDumpRequest dataDumpRequest = dataDumpUtil.checkAndPrepareAwaitingReqIfAny();
            if(dataDumpRequest!=null){
                dataDumpExportService.startDataDumpProcess(dataDumpRequest);
            }
        }
    }



    /**
     * To send an email for data dump export process.
     * @param totalRecordCount
     * @param failedBibs
     * @param exportedItemCount
     */
    private void processEmail(String totalRecordCount, String failedBibs, String exportedItemCount,String fetchType,String requestingInstitutionCode){
        if (transmissionType.equals(ScsbConstants.DATADUMP_TRANSMISSION_TYPE_S3)
                ||transmissionType.equals(ScsbConstants.DATADUMP_TRANSMISSION_TYPE_FILESYSTEM)) {
            dataDumpEmailService.sendEmail(institutionCodes,
                    Integer.valueOf(totalRecordCount),
                    Integer.valueOf(failedBibs),
                    transmissionType,
                    this.folderName,
                    toEmailId,
                    ScsbConstants.DATADUMP_DATA_AVAILABLE,
                    Integer.valueOf(exportedItemCount),fetchType,requestingInstitutionCode,imsDepositoryCodes
            );
        }
    }

    /**
     * Gets data dump email service.
     *
     * @return the data dump email service
     */
    public DataDumpEmailService getDataDumpEmailService() {
        return dataDumpEmailService;
    }

    /**
     * Sets data dump email service.
     *
     * @param dataDumpEmailService the data dump email service
     */
    public void setDataDumpEmailService(DataDumpEmailService dataDumpEmailService) {
        this.dataDumpEmailService = dataDumpEmailService;
    }

    /**
     * Gets transmission type.
     *
     * @return the transmission type
     */
    public String getTransmissionType() {
        return transmissionType;
    }

    /**
     * Sets transmission type.
     *
     * @param transmissionType the transmission type
     */
    public void setTransmissionType(String transmissionType) {
        this.transmissionType = transmissionType;
    }

    /**
     * Gets institution codes.
     *
     * @return the institution codes
     */
    public List<String> getInstitutionCodes() {
        return institutionCodes;
    }

    /**
     * Sets institution codes.
     *
     * @param institutionCodes the institution codes
     */
    public void setInstitutionCodes(List<String> institutionCodes) {
        this.institutionCodes = institutionCodes;
    }

    /**
     * Gets requesting institution code.
     *
     * @return the requesting institution code
     */
    public String getRequestingInstitutionCode() {
        return requestingInstitutionCode;
    }

    /**
     * Sets requesting institution code.
     *
     * @param requestingInstitutionCode the requesting institution code
     */
    public void setRequestingInstitutionCode(String requestingInstitutionCode) {
        this.requestingInstitutionCode = requestingInstitutionCode;
    }

    /**
     * Gets folder name.
     *
     * @return the folder name
     */
    public String getFolderName() {
        return folderName;
    }

    /**
     * Sets folder name.
     *
     * @param folderName the folder name
     */
    public void setFolderName(String folderName) {
        this.folderName = folderName;
    }

    /**
     * Gets to email id.
     *
     * @return the to email id
     */
    public String getToEmailId() {
        return toEmailId;
    }

    /**
     * Sets to email id.
     *
     * @param toEmailId the to email id
     */
    public void setToEmailId(String toEmailId) {
        this.toEmailId = toEmailId;
    }

    /**
     * Gets request id.
     *
     * @return the request id
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * Sets request id.
     *
     * @param requestId the request id
     */
    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    /**
     * Gets fetch type.
     *
     * @return the fetch type
     */
    public String getFetchType() {
        return fetchType;
    }

    /**
     * Sets fetch type.
     *
     * @param fetchType the fetch type
     */
    public void setFetchType(String fetchType) {
        this.fetchType = fetchType;
    }

    public List<String> getImsDepositoryCodes() {
        return imsDepositoryCodes;
    }

    public void setImsDepositoryCodes(List<String> imsDepositoryCodes) {
        this.imsDepositoryCodes = imsDepositoryCodes;
    }

    public void setRequestFromSwagger(boolean requestFromSwagger) {
        this.isRequestFromSwagger = requestFromSwagger;
    }

    public boolean getRequestFromSwagger() {
        return isRequestFromSwagger;
    }

    public Integer getEltRequestId() {
        return eltRequestId;
    }

    public void setEltRequestId(Integer eltRequestId) {
        this.eltRequestId = eltRequestId;
    }
}
