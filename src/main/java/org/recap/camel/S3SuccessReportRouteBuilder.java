package org.recap.camel;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.aws.s3.S3Constants;
import org.apache.camel.model.dataformat.BindyType;
import org.recap.PropertyKeyConstants;
import org.recap.ScsbConstants;
import org.recap.model.csv.SCSBCSVSuccessRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Created by angelind on 18/8/16.
 */
@Slf4j
@Component
public class S3SuccessReportRouteBuilder {

    /**
     * Instantiates a new Ftp success report route builder.
     *
     * @param context         the context
     * @param s3EtlReportsDir the s3 etl remote server
     */
    @Autowired
    public S3SuccessReportRouteBuilder(CamelContext context, @Value("${" + PropertyKeyConstants.S3_ADD_S3_ROUTES_ON_STARTUP + "}") boolean addS3RoutesOnStartup, @Value("${" + PropertyKeyConstants.S3_ETL_REPORTS_DIR + "}") String s3EtlReportsDir) {
        try {
            if (addS3RoutesOnStartup) {
                context.addRoutes(new RouteBuilder() {
                    @Override
                    public void configure() throws Exception {
                        from(ScsbConstants.FTP_SUCCESS_Q)
                                .routeId(ScsbConstants.FTP_FAILURE_ROUTE_ID)
                                .process(new FileNameProcessorForSuccessRecord())
                                .marshal().bindy(BindyType.Csv, SCSBCSVSuccessRecord.class)
                                .setHeader(S3Constants.KEY, simple(s3EtlReportsDir + "${in.header.directoryName}/${in.header.fileName}-${in.header.reportType}-${date:now:ddMMMyyyy}.csv"))
                                .to(ScsbConstants.SCSB_CAMEL_S3_TO_ENDPOINT);
                    }
                });
            }
        } catch (Exception e) {
            log.error(ScsbConstants.ERROR,e);
        }
    }
}
