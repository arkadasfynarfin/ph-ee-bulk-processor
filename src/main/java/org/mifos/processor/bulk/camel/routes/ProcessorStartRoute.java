package org.mifos.processor.bulk.camel.routes;

import org.mifos.processor.bulk.file.FileTransferService;
import org.mifos.processor.bulk.utility.Utils;
import org.mifos.processor.bulk.zeebe.ZeebeProcessStarter;
import org.mifos.processor.bulk.zeebe.worker.WorkerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.File;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.mifos.processor.bulk.camel.config.CamelProperties.TENANT_NAME;
import static org.mifos.processor.bulk.zeebe.ZeebeVariables.*;


@Component
public class ProcessorStartRoute extends BaseRouteBuilder {

    @Autowired
    private ZeebeProcessStarter zeebeProcessStarter;

    @Autowired
    @Qualifier("awsStorage")
    private FileTransferService fileTransferService;

    @Autowired
    protected WorkerConfig workerConfig;

    @Value("${application.bucket-name}")
    private String bucketName;

    @Value("${bpmn.flows.bulk-processor}")
    private String workflowId;

    @Value("${config.success-threshold-check.success-threshold}")
    private int successThreshold;

    @Value("${config.success-threshold-check.max-retry}")
    private int maxThresholdCheckRetry;

    @Value("${config.success-threshold-check.delay}")
    private int thresholdCheckDelay;

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Override
    public void configure() {
        setup();
    }

    private void setup() {
        from("rest:POST:/bulk/transfer/{requestId}/{fileName}")
                .unmarshal().mimeMultipart("multipart/*")
                .to("direct:validate-tenant")
                .process(exchange -> {
                    String fileName = System.currentTimeMillis() + "_" +  exchange.getIn().getHeader("fileName", String.class);
                    String requestId = exchange.getIn().getHeader("requestId", String.class);
                    String purpose = exchange.getIn().getHeader("purpose", String.class);
                    String batchId = UUID.randomUUID().toString();

                    if (purpose == null || purpose.isEmpty()) {
                        purpose = "test payment";
                    }

                    logger.info("\n\n Filename: " + fileName + " \n\n");
                    logger.info("\n\n BatchId: " + batchId + " \n\n");

                    File file = new File(fileName);
                    file.setWritable(true);
                    file.setReadable(true);

                    String csvData = exchange.getIn().getBody(String.class);
                    FileWriter fileWriter = new FileWriter(file);
                    fileWriter.write(csvData);
                    fileWriter.close();

                    logger.info(csvData);
                    logger.info(""+file.length());
                    logger.info(file.getAbsolutePath());

                    String nm = fileTransferService.uploadFile(file, bucketName);

                    logger.info("File uploaded {}", nm);

                    
                    Map<String, Object> variables = new HashMap<>();
                    variables.put(BATCH_ID, batchId);
                    variables.put(FILE_NAME, fileName);
                    variables.put(REQUEST_ID, requestId);
                    variables.put(PURPOSE, purpose);
                    variables.put(TENANT_ID, exchange.getProperty(TENANT_NAME));
                    variables.put(PARTY_LOOKUP_ENABLED, workerConfig.isPartyLookUpWorkerEnabled);
                    variables.put(APPROVAL_ENABLED, workerConfig.isApprovalWorkerEnabled);
                    variables.put(ORDERING_ENABLED, workerConfig.isOrderingWorkerEnabled);
                    variables.put(SPLITTING_ENABLED, workerConfig.isSplittingWorkerEnabled);
                    variables.put(FORMATTING_ENABLED, workerConfig.isFormattingWorkerEnabled);
                    variables.put(SUCCESS_THRESHOLD_CHECK_ENABLED, workerConfig.isSuccessThresholdCheckEnabled);
                    variables.put(MERGE_ENABLED, workerConfig.isMergeBackWorkerEnabled);
                    variables.put(MAX_STATUS_RETRY, maxThresholdCheckRetry);
                    variables.put(SUCCESS_THRESHOLD, successThreshold);
                    variables.put(THRESHOLD_DELAY, Utils.getZeebeTimerValue(thresholdCheckDelay));

                    zeebeProcessStarter.startZeebeWorkflow(workflowId, "", variables);
                    exchange.getIn().setBody(batchId);
                });

        from("direct:validate-tenant")
                .id("direct:validate-tenant")
                .log("Validating tenant")
                .process(exchange -> {
                    String tenantName = exchange.getIn().getHeader("Platform-TenantId", String.class);
                    if (tenantName == null || tenantName.isEmpty() || !tenants.contains(tenantName)) {
                        throw new Exception("Invalid tenant value.");
                    }
                    exchange.setProperty(TENANT_NAME, tenantName);
                });


    }
}
