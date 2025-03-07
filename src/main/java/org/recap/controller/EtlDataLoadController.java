package org.recap.controller;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.recap.PropertyKeyConstants;
import org.recap.camel.EtlDataLoadProcessor;
import org.recap.camel.RecordProcessor;
import org.recap.model.etl.EtlLoadRequest;
import org.recap.report.ReportGenerator;
import org.recap.repository.BibliographicDetailsRepository;
import org.recap.repository.HoldingsDetailsRepository;
import org.recap.repository.ItemDetailsRepository;
import org.recap.repository.XmlRecordRepository;
import org.recap.util.CommonUtil;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.io.File;
import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * Created by rajeshbabuk on 22/6/16.
 */
@Slf4j
@Controller
public class EtlDataLoadController {



    /**
     * The Camel context.
     */
    @Autowired
    CamelContext camelContext;

    /**
     * The Bibliographic details repository.
     */
    @Autowired
    BibliographicDetailsRepository bibliographicDetailsRepository;

    /**
     * The Holdings details repository.
     */
    @Autowired
    HoldingsDetailsRepository holdingsDetailsRepository;

    /**
     * The Item details repository.
     */
    @Autowired
    ItemDetailsRepository itemDetailsRepository;

    /**
     * The Xml record repository.
     */
    @Autowired
    XmlRecordRepository xmlRecordRepository;

    @Value("${" + PropertyKeyConstants.ETL_LOAD_BATCHSIZE + "}")
    private Integer batchSize;

    @Value("${" + PropertyKeyConstants.ETL_DATA_LOAD_DIRECTORY + "}")
    private String inputDirectoryPath;

    /**
     * The Record processor.
     */
    @Autowired
    RecordProcessor recordProcessor;

    /**
     * The Producer.
     */
    @Autowired
    ProducerTemplate producer;

    /**
     * The Report generator.
     */
    @Autowired
    ReportGenerator reportGenerator;

    @Autowired
    private CommonUtil commonUtil;

    /**
     * Loads the data load UI page.
     *
     * @param model the model
     * @return the string
     */
    @RequestMapping(value = "/", method = {RequestMethod.GET, RequestMethod.POST})
    public String etlDataLoader(Model model) {
        EtlLoadRequest etlLoadRequest = new EtlLoadRequest();
        model.addAttribute("etlLoadRequest", etlLoadRequest);
        return "etlDataLoader";
    }

    /**
     * This is the action method to start the data load process for the request that comes from UI.
     *
     * @param etlLoadRequest the etl load request
     * @param result         the result
     * @param model          the model
     * @return the string
     */
    @ResponseBody
    @PostMapping(value = "/etlDataLoader/bulkIngest")
    public String bulkIngest(@Valid @ModelAttribute("etlLoadRequest") EtlLoadRequest etlLoadRequest,
                            BindingResult result,
                            Model model) {
        EtlDataLoadProcessor etlDataLoadProcessor = new EtlDataLoadProcessor();

        String fileName = etlLoadRequest.getFileName();
        etlDataLoadProcessor.setBatchSize(etlLoadRequest.getBatchSize());
        etlDataLoadProcessor.setFileName(fileName);
        etlDataLoadProcessor.setInstitutionName(etlLoadRequest.getOwningInstitutionName());
        etlDataLoadProcessor.setXmlRecordRepository(xmlRecordRepository);
        etlDataLoadProcessor.setBibliographicDetailsRepository(bibliographicDetailsRepository);
        etlDataLoadProcessor.setHoldingsDetailsRepository(holdingsDetailsRepository);
        etlDataLoadProcessor.setItemDetailsRepository(itemDetailsRepository);
        etlDataLoadProcessor.setProducer(producer);
        etlDataLoadProcessor.setRecordProcessor(recordProcessor);
        etlDataLoadProcessor.startLoadProcess();
        return etlDataLoader(model);
    }

    /**
     * Generate data load status.
     *
     * @return the string
     */
    @ResponseBody
    @GetMapping(value = "/etlDataLoader/status")
    public String report() {
        String status = "Process Started";
        if (camelContext.getStatus().isStarted()) {
            status = "Running";
        }

        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Status  : " + status).append("\n");
        return stringBuilder.toString();
    }

    /**
     * This is the action method to upload the filed from UI to start data load process for them.
     *
     * @param etlLoadRequest the etl load request
     * @param result         the result
     * @param model          the model
     * @return the string
     * @throws IOException the io exception
     */
    @ResponseBody
    @PostMapping(value = "/etlDataLoader/uploadFiles")
    public String uploadFiles(@Valid @ModelAttribute("etlLoadRequest") EtlLoadRequest etlLoadRequest,
                             BindingResult result,
                             Model model) throws IOException {

        MultipartFile multipartFile = etlLoadRequest.getFile();
        if (null == multipartFile || StringUtils.isBlank(multipartFile.getOriginalFilename())) {
            return etlDataLoader(model);
        }
        File uploadFile = new File(multipartFile.getOriginalFilename());
        FileUtils.writeByteArrayToFile(uploadFile, etlLoadRequest.getFile().getBytes());
        FileUtils.copyFile(uploadFile, new File(inputDirectoryPath + File.separator + multipartFile.getOriginalFilename()));
        return etlDataLoader(model);
    }

    /**
     * Generate report for the data load process.
     *
     * @param etlLoadRequest the etl load request
     * @param result         the result
     * @param model          the model
     * @return the string
     */
    @ResponseBody
    @PostMapping(value = "/etlDataLoader/reports")
    public String generateReport(@Valid @ModelAttribute("etlLoadRequest") EtlLoadRequest etlLoadRequest,
                             BindingResult result,
                             Model model) {
        Calendar cal = Calendar.getInstance();
        Date dateFrom = etlLoadRequest.getDateFrom();
        cal.setTime(Objects.requireNonNullElseGet(dateFrom, Date::new));
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        Date from = cal.getTime();
        Date dateTo = etlLoadRequest.getDateTo();
        cal.setTime(Objects.requireNonNullElseGet(dateTo, Date::new));
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        Date to = cal.getTime();
        String generatedReportFileName = reportGenerator.generateReport(etlLoadRequest.getReportFileName(), etlLoadRequest.getOperationType(),etlLoadRequest.getReportType(), etlLoadRequest.getReportInstitutionName(),
                from, to, etlLoadRequest.getTransmissionType());
        if(StringUtils.isBlank(generatedReportFileName)){
            log.error("Report wasn't generated! Please contact help desk!");
        } else {
            log.info("Report successfully generated! : {} " , generatedReportFileName);
        }
        return etlDataLoader(model);
    }

    @GetMapping(value = "/etlDataLoad/institutions")
    @ResponseBody
    public List<String> getInstitution() {
        return commonUtil.findAllInstitutionCodesExceptSupportInstitution();
    }

}
