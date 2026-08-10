package org.recap.service.email.datadump;

import org.apache.camel.ProducerTemplate;
import org.recap.PropertyKeyConstants;
import org.recap.ScsbConstants;
import org.recap.camel.EmailPayLoad;
import org.recap.model.ILSConfigProperties;
import org.recap.util.PropertyUtil;
import org.recap.model.export.DataDumpRequest;
import org.recap.util.datadump.DataDumpUtil;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;
import java.util.Optional;

/**
 * Created by premkb on 21/9/16.
 */
@Service
public class DataDumpEmailService {

    @Value("${" + PropertyKeyConstants.ETL_DATA_DUMP_DIRECTORY + "}")
    private String fileSystemDataDumpDirectory;

    @Value("${" + PropertyKeyConstants.S3_DATA_DUMP_DIR + "}")
    private String ftpDataDumpDirectory;

    @Value("${" + PropertyKeyConstants.ETL_DATA_DUMP_FETCHTYPE_FULL + "}")
    private String dataDumpFetchType;

    @Value("${" + PropertyKeyConstants.DATA_DUMP_NOTIFICATION_CC + "}")
    private String dataDumpNotificationCC;

    @Autowired
    private DataDumpUtil dataDumpUtil;


    @Autowired
    private ProducerTemplate producer;

    @Autowired
    PropertyUtil propertyUtil;

    /**
     * Send email with the given parameters.
     * @param institutionCodes        the institution codes
     * @param totalRecordCount        the total record count
     * @param failedRecordCount       the failed record count
     * @param transmissionType        the transmission type
     * @param dateTimeStringForFolder the date time string for folder
     * @param toEmailAddress          the to email address
     * @param emailBodyFor            the email body for
     * @param fetchType
     * @param requestingInstitutionCode
     */
    public void sendEmail(List<String> institutionCodes, Integer totalRecordCount, Integer failedRecordCount, String transmissionType, String dateTimeStringForFolder, String toEmailAddress, String emailBodyFor, Integer exportedItemCount, String fetchType, String requestingInstitutionCode,List<String> imsDepositoryCodes) {
        if(fetchType.equals(dataDumpFetchType)) {
            EmailPayLoad emailPayLoad = new EmailPayLoad();
            emailPayLoad.setInstitutions(institutionCodes);
            emailPayLoad.setLocation(getLocation(transmissionType, dateTimeStringForFolder));
            emailPayLoad.setCount(totalRecordCount);
            emailPayLoad.setFailedCount(failedRecordCount);
            emailPayLoad.setTo(toEmailAddress);
            emailPayLoad.setItemCount(exportedItemCount);
            emailPayLoad.setImsDepositoryCodes(imsDepositoryCodes);
            producer.sendBodyAndHeader(ScsbConstants.EMAIL_Q, emailPayLoad, ScsbConstants.DATADUMP_EMAILBODY_FOR, emailBodyFor);
        }
        else if(fetchType.equals(ScsbConstants.DATADUMP_FETCHTYPE_INCREMENTAL)){
            setEmailForDatadump(transmissionType, emailBodyFor, dateTimeStringForFolder, toEmailAddress,
                    requestingInstitutionCode, ScsbConstants.SUBJECT_INCREMENTAL_DATA_DUMP, ScsbConstants.EMAIL_INCREMENTAL_DATA_DUMP);
        }
        else if(fetchType.equals(ScsbConstants.DATADUMP_DELETED_JSON_FORMAT)){
            setEmailForDatadump(transmissionType, emailBodyFor, dateTimeStringForFolder, toEmailAddress,
                    requestingInstitutionCode, ScsbConstants.SUBJECT_DELETION_DATA_DUMP, ScsbConstants.EMAIL_DELETION_DATA_DUMP);
        }
    }

    private String mailForCc(String requestingInstitutionCode) {
        ILSConfigProperties ilsConfigProperties = propertyUtil.getILSConfigProperties(requestingInstitutionCode);
        return ilsConfigProperties.getEmailDataDumpCc();
    }

    /**
     * Gets the location to write data dump files for the given transmission type.
     *
     * @param transmissionType
     * @param dateTimeStringForFolder
     * @return
     */
    private String getLocation(String transmissionType,String dateTimeStringForFolder) {
        String location = null;
        if ("0".equals(transmissionType)) {
            location = "S3 location - " + getFtpLocation(ftpDataDumpDirectory) + File.separator + dateTimeStringForFolder;
        } else if ("2".equals(transmissionType)) {
            location = "File System - " + fileSystemDataDumpDirectory + File.separator + dateTimeStringForFolder;
        }
        return location;
    }

    private static String getFtpLocation(String ftpLocation) {
        if (ftpLocation.contains(File.separator)) {
            String[] splittedFtpLocation = ftpLocation.split(File.separator,2);
            return splittedFtpLocation[1];
        } else {
            return ftpLocation;
        }
    }

    public void setEmailForDatadump(String transmissionType,String emailBodyFor, String dateTimeStringForFolder, String toEmailAddress,
                                     String requestingInstitutionCode, String subject, String emailBody)

    {
        EmailPayLoad emailPayLoad = new EmailPayLoad();
        emailPayLoad.setLocation(getLocation(transmissionType, dateTimeStringForFolder));
        emailPayLoad.setTo(toEmailAddress);
        emailPayLoad.setCc(mailForCc(requestingInstitutionCode));
        emailPayLoad.setSubject(emailBodyFor.equals(ScsbConstants.DATADUMP_NO_DATA_AVAILABLE)?(subject + " : "+requestingInstitutionCode+"(No data)"):(subject + " : "+requestingInstitutionCode));
        producer.sendBodyAndHeader(ScsbConstants.EMAIL_Q, emailPayLoad, ScsbConstants.DATADUMP_EMAILBODY_FOR, emailBodyFor.equals(ScsbConstants.DATADUMP_NO_DATA_AVAILABLE)?emailBodyFor:emailBody);

    }

    public void sendEmailNotification(DataDumpRequest dataDumpRequest) {
        Boolean isIncrementalSequence = Optional.ofNullable(dataDumpRequest.isIncrementalSequence()).orElse(false);
        if(!isIncrementalSequence) {
            sendEmailNotificationForExport(dataDumpRequest, false);
        }
    }

    public void sendEmailNotificationForExport(DataDumpRequest dataDumpRequest, Boolean isExportJob){
        String fetchType = dataDumpUtil.getFetchType(dataDumpRequest.getFetchType());
        String outputformat = dataDumpUtil.getOutputformat(dataDumpRequest.getOutputFileFormat());
        String transmissionType = dataDumpUtil.getTransmissionType(dataDumpRequest.getTransmissionType());
        EmailPayLoad emailPayLoad = new EmailPayLoad();
        if(isExportJob){
            emailPayLoad.setSubject("Data Dump Export Triggered with JOB");
            emailPayLoad.setTo(propertyUtil.getILSConfigProperties(dataDumpRequest.getRequestingInstitutionCode()).getEmailDataDumpTo());
        } else {
            emailPayLoad.setSubject("Notification : Initiated data-dump for Fetch type:" + fetchType);
            emailPayLoad.setTo(dataDumpRequest.getToEmailAddress());
            emailPayLoad.setCc(dataDumpNotificationCC);
        }
        emailPayLoad.setInstitutionsRequested(dataDumpRequest.getInstitutionCodes());
        emailPayLoad.setRequestingInstitution(dataDumpRequest.getRequestingInstitutionCode());
        emailPayLoad.setFetchType(fetchType);
        emailPayLoad.setCollectionGroupIds(dataDumpRequest.getCollectionGroupIds());
        emailPayLoad.setCollectionGroupCodes(dataDumpUtil.getCollectionGroupCodes(dataDumpRequest.getCollectionGroupIds()));
        emailPayLoad.setTransmissionType(transmissionType);
        emailPayLoad.setOutputFileFormat(outputformat);
        emailPayLoad.setImsDepositoryCodes(dataDumpRequest.getImsDepositoryCodes());
        emailPayLoad.setMessage(!transmissionType.equalsIgnoreCase("HTTP")?"Will send further notification upon completion.":"");
        producer.sendBodyAndHeader(ScsbConstants.EMAIL_Q, emailPayLoad, ScsbConstants.DATADUMP_EMAILBODY_FOR, ScsbConstants.DATADUMP_EXPORT_NOTIFICATION);
    }
}


