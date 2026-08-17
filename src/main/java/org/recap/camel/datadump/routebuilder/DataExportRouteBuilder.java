package org.recap.camel.datadump.routebuilder;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.recap.ScsbConstants;
import org.recap.camel.datadump.DataDumpSequenceProcessor;
import org.recap.camel.datadump.DataExportAggregator;
import org.recap.camel.datadump.DataExportPredicate;
import org.recap.camel.datadump.FileFormatProcessorForDataExport;
import org.recap.camel.datadump.TransmissionTypeProcessorForDataExport;
import org.recap.camel.datadump.consumer.BibEntityGeneratorActiveMQConsumer;
import org.recap.camel.datadump.consumer.DataExportCompletionStatusActiveMQConsumer;
import org.recap.camel.datadump.consumer.DeletedJsonFormatActiveMQConsumer;
import org.recap.camel.datadump.consumer.DeletedRecordFormatActiveMQConsumer;
import org.recap.camel.datadump.consumer.MarcRecordFormatActiveMQConsumer;
import org.recap.camel.datadump.consumer.MarcXMLFormatActiveMQConsumer;
import org.recap.camel.datadump.consumer.SCSBRecordFormatActiveMQConsumer;
import org.recap.camel.datadump.consumer.SCSBXMLFormatActiveMQConsumer;
import org.recap.model.export.DataDumpPropertyHolder;
import org.recap.repository.BibliographicDetailsRepository;
import org.recap.service.formatter.datadump.DeletedJsonFormatterService;
import org.recap.service.formatter.datadump.MarcXmlFormatterService;
import org.recap.service.formatter.datadump.SCSBXmlFormatterService;
import org.recap.util.XmlFormatter;

/**
 * Created by peris on 11/5/16.
 */
@Slf4j
public class DataExportRouteBuilder {



    /**
     * Instantiates a new Data export route builder.
     *
     * @param camelContext                   the camel context
     * @param bibliographicDetailsRepository the bibliographic details repository
     * @param marcXmlFormatterService        the marc xml formatter service
     * @param scsbXmlFormatterService        the scsb xml formatter service
     * @param deletedJsonFormatterService    the deleted json formatter service
     * @param xmlFormatter                   the xml formatter
     * @param dataExportCompletionStatusActiveMQConsumer    the data Export Completion Status ActiveMQ Consumer
     * @param dataDumpSequenceProcessor      the data Dump Sequence Processor
     * @param dataDumpPropertyHolder         the data Dump Property Holder
     */
    public DataExportRouteBuilder(CamelContext camelContext,
                                  BibliographicDetailsRepository bibliographicDetailsRepository,
                                  MarcXmlFormatterService marcXmlFormatterService,
                                  SCSBXmlFormatterService scsbXmlFormatterService,
                                  DeletedJsonFormatterService deletedJsonFormatterService,
                                  XmlFormatter xmlFormatter,
                                  DataExportCompletionStatusActiveMQConsumer dataExportCompletionStatusActiveMQConsumer,
                                  DataDumpSequenceProcessor dataDumpSequenceProcessor,
                                  DataDumpPropertyHolder dataDumpPropertyHolder) {
        try {

            camelContext.addRoutes(new RouteBuilder() {
                @Override
                public void configure() throws Exception {
                    from(ScsbConstants.SOLR_INPUT_FOR_DATA_EXPORT_Q)
                            .routeId(ScsbConstants.SOLR_INPUT_DATA_EXPORT_ROUTE_ID)
                            .threads(20)
                            .bean(new BibEntityGeneratorActiveMQConsumer(bibliographicDetailsRepository, dataDumpPropertyHolder.getDataDumpBibEntityThreadSize(), dataDumpPropertyHolder.getDataDumpBibEntityBatchSize()), "processBibEntities");
                }
            });

            camelContext.addRoutes(new RouteBuilder() {
                @Override
                public void configure() throws Exception {

                    interceptFrom(ScsbConstants.BIB_ENTITY_FOR_DATA_EXPORT_Q)
                            .process(new FileFormatProcessorForDataExport())
                            .process(new TransmissionTypeProcessorForDataExport());

                    from(ScsbConstants.BIB_ENTITY_FOR_DATA_EXPORT_Q)
                            .routeId(ScsbConstants.BIB_ENTITY_DATA_EXPORT_ROUTE_ID)
                            .threads(20)
                            .choice()
                            .when(header(ScsbConstants.EXPORT_FORMAT).isEqualTo(ScsbConstants.DATADUMP_XML_FORMAT_MARC))
                            .bean(new MarcRecordFormatActiveMQConsumer(marcXmlFormatterService, dataDumpPropertyHolder.getDataDumpMarcFormatThreadSize(), dataDumpPropertyHolder.getDataDumpMarcFormatBatchSize()), ScsbConstants.PROCESS_RECORDS)
                            .when(header(ScsbConstants.EXPORT_FORMAT).isEqualTo(ScsbConstants.DATADUMP_XML_FORMAT_SCSB))
                            .bean(new SCSBRecordFormatActiveMQConsumer(scsbXmlFormatterService, dataDumpPropertyHolder.getDataDumpScsbFormatThreadSize(), dataDumpPropertyHolder.getDataDumpScsbFormatBatchSize()), ScsbConstants.PROCESS_RECORDS)
                            .when(header(ScsbConstants.EXPORT_FORMAT).isEqualTo(ScsbConstants.DATADUMP_DELETED_JSON_FORMAT))
                            .bean(new DeletedRecordFormatActiveMQConsumer(deletedJsonFormatterService, dataDumpPropertyHolder.getDataDumpDeletedRecordsThreadSize(), dataDumpPropertyHolder.getDataDumpDeletedRecordsBatchSize()), ScsbConstants.PROCESS_RECORDS)
                    ;

                }
            });

            camelContext.addRoutes(new RouteBuilder() {
                @Override
                public void configure() throws Exception {

                    from(ScsbConstants.MARC_RECORD_FOR_DATA_EXPORT_Q)
                            .routeId(ScsbConstants.MARC_RECORD_DATA_EXPORT_ROUTE_ID)
                            .aggregate(constant(true), new DataExportAggregator()).completionPredicate(new DataExportPredicate(Integer.valueOf(dataDumpPropertyHolder.getDataDumpRecordsPerFile())))
                            .bean(new MarcXMLFormatActiveMQConsumer(marcXmlFormatterService), "processMarcXmlString")
                            .to(ScsbConstants.DATADUMP_STAGING_Q);
                }
            });

            camelContext.addRoutes(new RouteBuilder() {
                @Override
                public void configure() throws Exception {
                    from(ScsbConstants.SCSB_RECORD_FOR_DATA_EXPORT_Q)
                            .routeId(ScsbConstants.SCSB_RECORD_DATA_EXPORT_ROUTE_ID)
                            .aggregate(constant(true), new DataExportAggregator()).completionPredicate(new DataExportPredicate(Integer.valueOf(dataDumpPropertyHolder.getDataDumpRecordsPerFile())))
                            .bean(new SCSBXMLFormatActiveMQConsumer(scsbXmlFormatterService, xmlFormatter), "processSCSBXmlString")
                            .to(ScsbConstants.DATADUMP_STAGING_Q);
                }
            });

            camelContext.addRoutes(new RouteBuilder() {
                @Override
                public void configure() throws Exception {
                    from(ScsbConstants.DELETED_JSON_RECORD_FOR_DATA_EXPORT_Q)
                            .routeId(ScsbConstants.DELETED_JSON_RECORD_DATA_EXPORT_ROUTE_ID)
                            .aggregate(constant(true), new DataExportAggregator()).completionPredicate(new DataExportPredicate(Integer.valueOf(dataDumpPropertyHolder.getDataDumpRecordsPerFile())))
                            .bean(new DeletedJsonFormatActiveMQConsumer(deletedJsonFormatterService), "processDeleteJsonString")
                            .to(ScsbConstants.DATADUMP_STAGING_Q);
                }
            });

            camelContext.addRoutes(new RouteBuilder() {
                @Override
                public void configure() throws Exception {
                    from(ScsbConstants.DATADUMP_STAGING_Q)
                            .routeId(ScsbConstants.DATADUMP_STAGING_ROUTE_ID)
                            .choice()
                            .when(header("transmissionType").isEqualTo(ScsbConstants.DATADUMP_TRANSMISSION_TYPE_S3))
                            .to(ScsbConstants.DATADUMP_ZIPFILE_FTP_Q)
                            .when(header("transmissionType").isEqualTo(ScsbConstants.DATADUMP_TRANSMISSION_TYPE_HTTP))
                            .to(ScsbConstants.DATADUMP_HTTP_Q);
                }
            });

            // Router for FTP process completion and tracking with message
            camelContext.addRoutes(new RouteBuilder() {
                @Override
                public void configure() throws Exception {
                    from(ScsbConstants.DATA_DUMP_COMPLETION_FROM)
                            .routeId(ScsbConstants.DATA_DUMP_COMPLETION_ROUTE_ID)
                            .process(dataDumpSequenceProcessor)
                            .onCompletion().log(ScsbConstants.DATA_DUMP_COMPLETION_LOG);
                }
            });

            camelContext.addRoutes(new RouteBuilder() {
                @Override
                public void configure() throws Exception {
                    from(ScsbConstants.DATA_DUMP_COMPLETION_TOPIC)
                            .routeId(ScsbConstants.DATA_DUMP_COMPLETION_TOPIC_ROUTE_ID)
                            .bean(dataExportCompletionStatusActiveMQConsumer, "onCompletionTopicMessage");
                }
            });

        } catch (Exception e) {
            log.error(ScsbConstants.ERROR, e);
        }
    }
}
