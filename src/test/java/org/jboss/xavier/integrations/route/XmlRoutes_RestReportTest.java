package org.jboss.xavier.integrations.route;

import org.apache.camel.Exchange;
import org.apache.camel.builder.AdviceWithRouteBuilder;
import org.apache.commons.io.IOUtils;
import org.jboss.xavier.Application;
import org.jboss.xavier.analytics.pojo.output.AnalysisModel;
import org.jboss.xavier.analytics.pojo.output.workload.inventory.WorkloadInventoryReportModel;
import org.jboss.xavier.integrations.jpa.service.AnalysisService;
import org.jboss.xavier.integrations.jpa.service.FlagService;
import org.jboss.xavier.integrations.jpa.service.InitialSavingsEstimationReportService;
import org.jboss.xavier.integrations.jpa.service.WorkloadInventoryReportService;
import org.jboss.xavier.integrations.jpa.service.WorkloadService;
import org.jboss.xavier.integrations.jpa.service.WorkloadSummaryReportService;
import org.jboss.xavier.integrations.route.dataformat.CustomizedMultipartDataFormat;
import org.jboss.xavier.integrations.route.model.PageBean;
import org.jboss.xavier.integrations.route.model.SortBean;
import org.jboss.xavier.integrations.route.model.WorkloadInventoryFilterBean;
import org.jboss.xavier.integrations.util.TestUtil;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = {Application.class}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class XmlRoutes_RestReportTest extends XavierCamelTest {
    @Autowired
    private TestRestTemplate restTemplate;

    @MockBean
    private InitialSavingsEstimationReportService initialSavingsEstimationReportService;

    @SpyBean
    private WorkloadInventoryReportService workloadInventoryReportService;

    @MockBean
    private WorkloadService workloadService;

    @MockBean
    private FlagService flagService;

    @SpyBean
    private AnalysisService analysisService;

    @SpyBean
    private WorkloadSummaryReportService workloadSummaryReportService;

    @Value("${camel.component.servlet.mapping.context-path}")
    String camel_context;

    @Before
    public void setup() {
        camel_context = camel_context.substring(0, camel_context.indexOf("*"));
    }

    @Test
    public void xmlRouteBuilder_RestReport_NoParamGiven_ShouldCallFindReports() throws Exception {
        //Given


        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("reports-get-all");

        HttpHeaders headers = new HttpHeaders();
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());
        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        ResponseEntity<String> response = restTemplate.exchange(camel_context + "report/", HttpMethod.GET, entity, String.class);

        //Then
        verify(analysisService).findAllByOwner("mrizzi@redhat.com", 0, 10);
        assertThat(response).isNotNull();
        assertThat(response.getBody()).contains("\"content\":[]");
        assertThat(response.getBody()).contains("\"size\":10");
        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestReport_NoRHIdentityGiven_ShouldReturnForbidden() throws Exception {
        //Given


        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("reports-get-all");
        ResponseEntity<String> result = restTemplate.getForEntity(camel_context + "report", String.class);

        //Then
        assertThat(result).isNotNull();
        assertThat(result.getStatusCodeValue()).isEqualByComparingTo(403);
        assertThat(result.getBody()).isEqualTo("Forbidden");
        verifyZeroInteractions(analysisService);
        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestReport_PageAndSizeParamGiven_ShouldCallFindReports() throws Exception {
        //Given

        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("reports-get-all");
        Map<String, Object> variables = new HashMap<>();
        int page = 2;
        variables.put("page", page);
        int size = 3;
        variables.put("size", size);

        HttpHeaders headers = new HttpHeaders();
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());
        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        ResponseEntity<String> response = restTemplate.exchange(camel_context + "report?page={page}&size={size}", HttpMethod.GET, entity, String.class, variables);

        //Then
        verify(analysisService).findAllByOwner("mrizzi@redhat.com", page, size);
        assertThat(response).isNotNull();
        assertThat(response.getBody()).contains("\"content\":[]");
        assertThat(response.getBody()).contains("\"size\":3");
        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestReport_FilterTextPageAndSizeParamGiven_ShouldCallFindReports() throws Exception {
        //Given


        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("reports-get-all");
        Map<String, Object> variables = new HashMap<>();
        int page = 2;
        variables.put("page", page);
        int size = 3;
        variables.put("size", size);
        String filterText = "my report name which I'm searching";
        variables.put("filterText", filterText);

        HttpHeaders headers = new HttpHeaders();
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());
        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        ResponseEntity<String> response = restTemplate.exchange(camel_context + "report?page={page}&size={size}&filterText={filterText}", HttpMethod.GET, entity, String.class, variables);

        //Then
        verify(analysisService).findByOwnerAndReportName("mrizzi@redhat.com", filterText, page, size);
        assertThat(response).isNotNull();
        assertThat(response.getBody()).contains("\"content\":[]");
        assertThat(response.getBody()).contains("\"size\":3");
        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestReportId_IdParamGiven_ShouldCallFindById() throws Exception {
        //Given

        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("report-get-details");
        camelContext.startRoute("add-username-header");

        Map<String, Long> variables = new HashMap<>();
        Long one = 1L;
        variables.put("id", one);

        HttpHeaders headers = new HttpHeaders();
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());
        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        ResponseEntity<String> response = restTemplate.exchange(camel_context + "report/{id}", HttpMethod.GET, entity, String.class, variables);

        //Then
        verify(analysisService).findByOwnerAndId("mrizzi@redhat.com", one);
        assertThat(response).isNotNull();
        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestReportIdInitialSavingsEstimation_IdParamGiven_ShouldCallFindOneByAnalysisId() throws Exception {

        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("reports-get-details");

        HttpHeaders headers = new HttpHeaders();
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());
        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        Map<String, Object> variables = new HashMap<>();
        Long one = 1L;
        variables.put("id", one);

        ResponseEntity<String> response = restTemplate.exchange(camel_context + "report/{id}/initial-saving-estimation", HttpMethod.GET, entity, String.class, variables);

        //Then
        verify(initialSavingsEstimationReportService).findByAnalysisOwnerAndAnalysisId("mrizzi@redhat.com", one);
        assertThat(response).isNotNull();
        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestReportIdWorkloadInventory_IdParamGiven_PageParamGiven_SizeParamGiven_ShouldCallFindByAnalysisId() throws Exception {
        //Given

        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("to-paginationBean");
        camelContext.startRoute("to-sortBean");
        camelContext.startRoute("to-workloadInventoryFilterBean");
        camelContext.startRoute("workload-inventory-report-get-details");
        Map<String, Object> variables = new HashMap<>();
        Long one = 1L;
        variables.put("id", one);
        int page = 2;
        variables.put("page", page);
        int size = 3;
        variables.put("size", size);

        HttpHeaders headers = new HttpHeaders();
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());
        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        ResponseEntity<String> response = restTemplate.exchange(camel_context + "report/{id}/workload-inventory?page={page}&size={size}", HttpMethod.GET, entity, String.class, variables);

        //Then
        PageBean pageBean = new PageBean(page, size);
        SortBean sortBean = new SortBean(null, true);
        WorkloadInventoryFilterBean filterBean = new WorkloadInventoryFilterBean();

        verify(workloadInventoryReportService).findByAnalysisOwnerAndAnalysisId("mrizzi@redhat.com", one, pageBean, sortBean, filterBean);
        assertThat(response).isNotNull();
        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestReportIdWorkloadInventory_IdParamGiven_ShouldCallFindByAnalysisId() throws Exception {
        //Given

        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("to-paginationBean");
        camelContext.startRoute("to-sortBean");
        camelContext.startRoute("to-workloadInventoryFilterBean");
        camelContext.startRoute("workload-inventory-report-get-details");
        Map<String, Object> variables = new HashMap<>();
        Long one = 1L;
        variables.put("id", one);

        HttpHeaders headers = new HttpHeaders();
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());
        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        ResponseEntity<String> response = restTemplate.exchange(camel_context + "report/{id}/workload-inventory", HttpMethod.GET, entity, String.class, variables);

        //Then
        PageBean pageBean = new PageBean(0, 10);
        SortBean sortBean = new SortBean(null, true);
        WorkloadInventoryFilterBean filterBean = new WorkloadInventoryFilterBean();

        verify(workloadInventoryReportService).findByAnalysisOwnerAndAnalysisId("mrizzi@redhat.com", one, pageBean, sortBean, filterBean);
        assertThat(response).isNotNull();
        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestReportIdWorkloadInventory_IdParamGiven_SortParamGiven_ShouldCallFindByAnalysisId() throws Exception {
        //Given

        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("to-paginationBean");
        camelContext.startRoute("to-sortBean");
        camelContext.startRoute("to-workloadInventoryFilterBean");
        camelContext.startRoute("workload-inventory-report-get-details");
        Map<String, Object> variables = new HashMap<>();
        Long one = 1L;
        variables.put("id", one);
        String orderBy = "vmName";
        variables.put("orderBy", orderBy);
        Boolean orderAsc = true;
        variables.put("orderAsc", orderAsc);

        HttpHeaders headers = new HttpHeaders();
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());
        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        ResponseEntity<String> response = restTemplate.exchange(camel_context + "report/{id}/workload-inventory?orderBy={orderBy}&orderAsc={orderAsc}", HttpMethod.GET, entity, String.class, variables);

        //Then
        PageBean pageBean = new PageBean(0, 10);
        SortBean sortBean = new SortBean(orderBy, orderAsc);
        WorkloadInventoryFilterBean filterBean = new WorkloadInventoryFilterBean();

        verify(workloadInventoryReportService).findByAnalysisOwnerAndAnalysisId("mrizzi@redhat.com", one, pageBean, sortBean, filterBean);
        assertThat(response).isNotNull();
        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestReportIdWorkloadInventory_IdParamGiven_FiltersGiven_ShouldCallFindByAnalysisId() throws Exception {
        //Given

        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("to-paginationBean");
        camelContext.startRoute("to-sortBean");
        camelContext.startRoute("to-workloadInventoryFilterBean");
        camelContext.startRoute("workload-inventory-report-get-details");

        Map<String, Object> variables = new HashMap<>();

        Long one = 1L;
        variables.put("id", one);

        String provider1 = "my provider1";
        variables.put("provider1", provider1);
        String provider2 = "my provider2";
        variables.put("provider2", provider2);

        String cluster1 = "my cluster1";
        variables.put("cluster1", cluster1);
        String cluster2 = "my cluster2";
        variables.put("cluster2", cluster2);

        String datacenter1 = "my datacenter1";
        variables.put("datacenter1", datacenter1);
        String datacenter2 = "my datacenter2";
        variables.put("datacenter2", datacenter2);

        String vmName1 = "my vmName1";
        variables.put("vmName1", vmName1);
        String vmName2 = "my vmName2";
        variables.put("vmName2", vmName2);

        String osName1 = "my osName1";
        variables.put("osName1", osName1);
        String osName2 = "my osName2";
        variables.put("osName2", osName2);

        String workload1 = "my workload1";
        variables.put("workload1", workload1);
        String workload2 = "my workload2";
        variables.put("workload2", workload2);

        String recommendedTarget1 = "my recommendedTarget1";
        variables.put("recommendedTarget1", recommendedTarget1);
        String recommendedTarget2 = "my recommendedTarget2";
        variables.put("recommendedTarget2", recommendedTarget2);

        String flag1 = "my flag1";
        variables.put("flag1", flag1);
        String flag2 = "my flag2";
        variables.put("flag2", flag2);

        String complexity1 = "my complexity1";
        variables.put("complexity1", complexity1);
        String complexity2 = "my complexity2";
        variables.put("complexity2", complexity2);

        StringBuilder sb = new StringBuilder(camel_context + "report/{id}/workload-inventory?")
                .append("provider={provider1}&")
                .append("provider={provider2}&")
                .append("cluster={cluster1}&")
                .append("cluster={cluster2}&")
                .append("datacenter={datacenter1}&")
                .append("datacenter={datacenter2}&")
                .append("vmName={vmName1}&")
                .append("vmName={vmName2}&")
                .append("osName={osName1}&")
                .append("osName={osName2}&")
                .append("workload={workload1}&")
                .append("workload={workload2}&")
                .append("recommendedTargetIMS={recommendedTarget1}&")
                .append("recommendedTargetIMS={recommendedTarget2}&")
                .append("flagIMS={flag1}&")
                .append("flagIMS={flag2}&")
                .append("complexity={complexity1}&")
                .append("complexity={complexity2}");

        HttpHeaders headers = new HttpHeaders();
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());
        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        ResponseEntity<String> response = restTemplate.exchange(sb.toString(), HttpMethod.GET, entity, String.class, variables);

        //Then
        PageBean pageBean = new PageBean(0, 10);
        SortBean sortBean = new SortBean(null, true);
        WorkloadInventoryFilterBean filterBean = new WorkloadInventoryFilterBean();
        filterBean.setProviders(new HashSet<>(Arrays.asList(provider1, provider2)));
        filterBean.setClusters(new HashSet<>(Arrays.asList(cluster1, cluster2)));
        filterBean.setDatacenters(new HashSet<>(Arrays.asList(datacenter1, datacenter2)));;
        filterBean.setVmNames(new HashSet<>(Arrays.asList(vmName1, vmName2)));;
        filterBean.setOsNames(new HashSet<>(Arrays.asList(osName1, osName2)));;
        filterBean.setWorkloads(new HashSet<>(Arrays.asList(workload1, workload2)));
        filterBean.setRecommendedTargetsIMS(new HashSet<>(Arrays.asList(recommendedTarget1, recommendedTarget2)));
        filterBean.setFlagsIMS(new HashSet<>(Arrays.asList(flag1, flag2)));
        filterBean.setComplexities(new HashSet<>(Arrays.asList(complexity1, complexity2)));

        verify(workloadInventoryReportService).findByAnalysisOwnerAndAnalysisId("mrizzi@redhat.com", one, pageBean, sortBean, filterBean);
        assertThat(response).isNotNull();
        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestReportId_IdParamGiven_AndIdNotExists_ShouldReturnNotFound404Status() throws Exception {
        //Given

        Long one = 1L;
        when(analysisService.findByOwnerAndId("mrizzi@redhat.com", one)).thenReturn(null);

        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("report-delete");
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", one);

        HttpHeaders headers = new HttpHeaders();
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());
        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        ResponseEntity<String> response = restTemplate.exchange(camel_context + "report/{id}", HttpMethod.DELETE, entity, String.class, variables);

        //Then
        Assert.assertEquals(response.getStatusCodeValue(), HttpServletResponse.SC_NOT_FOUND);
        verify(analysisService).findByOwnerAndId("mrizzi@redhat.com", one);
        verify(analysisService, never()).deleteById(one);
        assertThat(response).isNotNull();
        assertThat(response.getBody()).contains("Analysis not found");
        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestReportId_IdParamGiven_AndIdExists_ShouldCallDeleteById() throws Exception {
        //Given

        Long one = 1L;
        when(analysisService.findByOwnerAndId("mrizzi@redhat.com",one)).thenReturn(new AnalysisModel());
        doNothing().when(analysisService).deleteById(one);

        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("report-delete");
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", one);

        HttpHeaders headers = new HttpHeaders();
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());
        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        ResponseEntity<String> response = restTemplate.exchange(camel_context + "report/{id}", HttpMethod.DELETE, entity, String.class, variables);

        //Then
        Assert.assertEquals(response.getStatusCodeValue(), HttpServletResponse.SC_NO_CONTENT);
        Assert.assertNull(response.getBody());
        verify(analysisService).findByOwnerAndId("mrizzi@redhat.com",one);
        verify(analysisService).deleteById(one);
        assertThat(response).isNotNull();
        assertThat(response.getBody()).isNull();
        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestReportIdWorkloadInventory_IdParamGiven_ShouldCallFindByAnalysisIdAndReturnCsv() throws Exception {
        //Given

        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("workload-inventory-report-get-details-as-csv");
        camelContext.startRoute("workload-inventory-report-model-to-csv");
        Map<String, Object> variables = new HashMap<>();
        Long one = 1L;
        variables.put("id", one);
        HttpHeaders headers = new HttpHeaders();
        headers.add("whatever", "this header should not be copied");
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange(camel_context + "report/{id}/workload-inventory/csv" , HttpMethod.GET, entity, String.class, variables);

        //Then
        verify(workloadInventoryReportService).findByAnalysisOwnerAndAnalysisId("mrizzi@redhat.com", one);
        Assert.assertTrue(response.getHeaders().get("Content-Type").contains("text/csv"));
        Assert.assertTrue(response.getHeaders().get("Content-Disposition").contains("attachment;filename=workloadInventory_1.csv"));
        Assert.assertNull(response.getHeaders().get("whatever"));
        assertThat(response).isNotNull();
        assertThat(response.getBody()).contains("Provider,Datacenter,Cluster,VM name,OS type,Operating system description,Disk space,Memory,CPU cores,Workload,Effort,Recommended targets,Flags IMS,Product,Version,HostName");
        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestReportIdWorkloadInventory_IdParamGiven_ShouldCallFindByAnalysisIdAndReturnFilteredCsvUsingDefaultOrder() throws Exception {
        //Given
        AnalysisModel analysisModel = analysisService.buildAndSave("report name", "report desc", "file name", "mrizzi@redhat.com");


        List<WorkloadInventoryReportModel> workloadInventoryReportModels = new ArrayList<>();

        WorkloadInventoryReportModel wir1 = new WorkloadInventoryReportModel();
        wir1.setProvider("ProviderB");
        wir1.setDatacenter("DatacenterB");
        wir1.setCluster("ClusterB");
        wir1.setVmName("VmNameB");
        workloadInventoryReportModels.add(wir1);

        WorkloadInventoryReportModel wir2 = new WorkloadInventoryReportModel();
        wir2.setProvider("ProviderB");
        wir2.setDatacenter("DatacenterA");
        wir2.setCluster("ClusterB");
        wir2.setVmName("VmNameA");
        workloadInventoryReportModels.add(wir2);

        WorkloadInventoryReportModel wir3 = new WorkloadInventoryReportModel();
        wir3.setProvider("ProviderA");
        wir3.setDatacenter("DatacenterC");
        wir3.setCluster("ClusterA");
        wir3.setVmName("VmNameC");
        workloadInventoryReportModels.add(wir3);

        analysisService.addWorkloadInventoryReportModels(workloadInventoryReportModels, analysisModel.getId());


        Map<String, Object> variables = new HashMap<>();
        variables.put("id", analysisModel.getId());

        HttpHeaders headers = new HttpHeaders();
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());
        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("to-sortBean");
        camelContext.startRoute("to-workloadInventoryFilterBean");
        camelContext.startRoute("filtered-workload-inventory-report-get-details-as-csv");
        camelContext.startRoute("workload-inventory-report-model-to-csv");

        ResponseEntity<String> response = restTemplate.exchange(camel_context + "report/{id}/workload-inventory/filtered-csv", HttpMethod.GET, entity, String.class, variables);

        //Then
        SortBean sortBean = new SortBean(null, true);
        WorkloadInventoryFilterBean filterBean = new WorkloadInventoryFilterBean();

        verify(workloadInventoryReportService).findByAnalysisOwnerAndAnalysisId(analysisModel.getOwner(), analysisModel.getId(), sortBean, filterBean);
        Assert.assertTrue(response.getHeaders().get("Content-Type").contains("text/csv"));
        Assert.assertTrue(response.getHeaders().get("Content-Disposition").contains("attachment;filename=workloadInventory_1.csv"));
        Assert.assertNull(response.getHeaders().get("whatever"));
        assertThat(response).isNotNull();
        assertThat(response.getBody()).isNotNull();


        String[] rows = response.getBody().split("\n");

//      Expected CSV result
//      ProviderA | DatacenterC | ClusterA | VmNameC
//      ProviderB | DatacenterA | ClusterB | VmNameA
//      ProviderB | DatacenterB | ClusterB | VmNameB

        assertThat(rows[0]).contains("Provider,Datacenter,Cluster,VM name,OS type,Operating system description,Disk space,Memory,CPU cores,Workload,Effort,Recommended targets,Flags IMS,Product,Version,HostName");
        assertThat(rows[1]).contains("ProviderA,DatacenterC,ClusterA,VmNameC");
        assertThat(rows[2]).contains("ProviderB,DatacenterA,ClusterB,VmNameA");
        assertThat(rows[3]).contains("ProviderB,DatacenterB,ClusterB,VmNameB");
        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestReportIdWorkloadInventory_IdParamGiven_ShouldCallFindByAnalysisIdAndReturnFilteredCsv() throws Exception {
        //Given
        AnalysisModel analysisModel = analysisService.buildAndSave("report name", "report desc", "file name", "mrizzi@redhat.com");

        List<WorkloadInventoryReportModel> workloadInventoryReportModels = new ArrayList<>();

        WorkloadInventoryReportModel wir1 = new WorkloadInventoryReportModel();
        wir1.setProvider("my providerB");
        wir1.setDatacenter("my datacenter1");
        workloadInventoryReportModels.add(wir1);

        WorkloadInventoryReportModel wir2 = new WorkloadInventoryReportModel();
        wir2.setProvider("my providerA");
        wir2.setDatacenter("my datacenter2");
        workloadInventoryReportModels.add(wir2);

        WorkloadInventoryReportModel wir3 = new WorkloadInventoryReportModel();
        wir3.setProvider("provider");
        wir3.setDatacenter("datacenter");
        workloadInventoryReportModels.add(wir3);

        analysisService.addWorkloadInventoryReportModels(workloadInventoryReportModels, analysisModel.getId());


        Map<String, Object> variables = new HashMap<>();
        variables.put("id", analysisModel.getId());

        String orderBy = "provider";
        variables.put("orderBy", orderBy);
        Boolean orderAsc = true;
        variables.put("orderAsc", orderAsc);

        String datacenter1 = "my datacenter1";
        variables.put("datacenter1", datacenter1);
        String datacenter2 = "my datacenter2";
        variables.put("datacenter2", datacenter2);

        StringBuilder sb = new StringBuilder("")
                .append("orderBy={orderBy}&")
                .append("orderAsc={orderAsc}&")
                .append("datacenter={datacenter1}&")
                .append("datacenter={datacenter2}");

        HttpHeaders headers = new HttpHeaders();
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());
        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("to-sortBean");
        camelContext.startRoute("to-workloadInventoryFilterBean");
        camelContext.startRoute("filtered-workload-inventory-report-get-details-as-csv");
        camelContext.startRoute("workload-inventory-report-model-to-csv");

        ResponseEntity<String> response = restTemplate.exchange(camel_context + "report/{id}/workload-inventory/filtered-csv?" + sb.toString(), HttpMethod.GET, entity, String.class, variables);

        //Then
        SortBean sortBean = new SortBean(orderBy, orderAsc);

        WorkloadInventoryFilterBean filterBean = new WorkloadInventoryFilterBean();
        filterBean.setDatacenters(new HashSet<>(Arrays.asList(datacenter1, datacenter2)));

        verify(workloadInventoryReportService).findByAnalysisOwnerAndAnalysisId(analysisModel.getOwner(), analysisModel.getId(), sortBean, filterBean);
        Assert.assertTrue(response.getHeaders().get("Content-Type").contains("text/csv"));
        Assert.assertTrue(response.getHeaders().get("Content-Disposition").contains("attachment;filename=workloadInventory_1.csv"));
        assertThat(response).isNotNull();
        assertThat(response.getBody()).isNotNull();

        String[] rows = response.getBody().split("\n");
        assertThat(rows.length).isEqualTo(3);

        assertThat(response.getBody()).contains("Provider,Datacenter,Cluster,VM name,OS type,Operating system description,Disk space,Memory,CPU cores,Workload,Effort,Recommended targets,Flags IMS,Product,Version,HostName");
        assertThat(response.getBody()).contains("my providerA,my datacenter2");
        assertThat(response.getBody()).contains("my providerB,my datacenter1");
        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestReportIdWorkloadInventory_IdParamGiven_FilterAndSortParamsGiven_ShouldCallFindByAnalysisId_usingRightParams_AndReturnEmptyFilteredCsv() throws Exception {
        //Given
        AnalysisModel analysisModel = analysisService.buildAndSave("report name", "report desc", "file name", "mrizzi@redhat.com");

        List<WorkloadInventoryReportModel> workloadInventoryReportModels = new ArrayList<>();

        WorkloadInventoryReportModel wir1 = new WorkloadInventoryReportModel();
        wir1.setProvider("my provider1");
        wir1.setDatacenter("my datacenter1");
        workloadInventoryReportModels.add(wir1);

        WorkloadInventoryReportModel wir2 = new WorkloadInventoryReportModel();
        wir2.setProvider("my provider2");
        wir2.setDatacenter("my datacenter2");
        workloadInventoryReportModels.add(wir2);

        analysisService.addWorkloadInventoryReportModels(workloadInventoryReportModels, analysisModel.getId());


        Map<String, Object> variables = new HashMap<>();
        variables.put("id", analysisModel.getId());

        String orderBy = "provider";
        variables.put("orderBy", orderBy);
        Boolean orderAsc = true;
        variables.put("orderAsc", orderAsc);

        String provider1 = "my provider1";
        variables.put("provider1", provider1);
        String provider2 = "my provider2";
        variables.put("provider2", provider2);

        String cluster1 = "my cluster1";
        variables.put("cluster1", cluster1);
        String cluster2 = "my cluster2";
        variables.put("cluster2", cluster2);

        String datacenter1 = "my datacenter1";
        variables.put("datacenter1", datacenter1);
        String datacenter2 = "my datacenter2";
        variables.put("datacenter2", datacenter2);

        String vmName1 = "my vmName1";
        variables.put("vmName1", vmName1);
        String vmName2 = "my vmName2";
        variables.put("vmName2", vmName2);

        String osName1 = "my osName1";
        variables.put("osName1", osName1);
        String osName2 = "my osName2";
        variables.put("osName2", osName2);

        String workload1 = "my workload1";
        variables.put("workload1", workload1);
        String workload2 = "my workload2";
        variables.put("workload2", workload2);

        String recommendedTarget1 = "my recommendedTarget1";
        variables.put("recommendedTarget1", recommendedTarget1);
        String recommendedTarget2 = "my recommendedTarget2";
        variables.put("recommendedTarget2", recommendedTarget2);

        String flag1 = "my flag1";
        variables.put("flag1", flag1);
        String flag2 = "my flag2";
        variables.put("flag2", flag2);

        String complexity1 = "my complexity1";
        variables.put("complexity1", complexity1);
        String complexity2 = "my complexity2";
        variables.put("complexity2", complexity2);

        StringBuilder sb = new StringBuilder("")
                .append("orderBy={orderBy}&")
                .append("orderAsc={orderAsc}&")
                .append("provider={provider1}&")
                .append("provider={provider2}&")
                .append("cluster={cluster1}&")
                .append("cluster={cluster2}&")
                .append("datacenter={datacenter1}&")
                .append("datacenter={datacenter2}&")
                .append("vmName={vmName1}&")
                .append("vmName={vmName2}&")
                .append("osName={osName1}&")
                .append("osName={osName2}&")
                .append("workload={workload1}&")
                .append("workload={workload2}&")
                .append("recommendedTargetIMS={recommendedTarget1}&")
                .append("recommendedTargetIMS={recommendedTarget2}&")
                .append("flagIMS={flag1}&")
                .append("flagIMS={flag2}&")
                .append("complexity={complexity1}&")
                .append("complexity={complexity2}");

        HttpHeaders headers = new HttpHeaders();
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());
        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("to-sortBean");
        camelContext.startRoute("to-workloadInventoryFilterBean");
        camelContext.startRoute("filtered-workload-inventory-report-get-details-as-csv");
        camelContext.startRoute("workload-inventory-report-model-to-csv");

        ResponseEntity<String> response = restTemplate.exchange(camel_context + "report/{id}/workload-inventory/filtered-csv?" + sb.toString(), HttpMethod.GET, entity, String.class, variables);

        //Then
        SortBean sortBean = new SortBean(orderBy, orderAsc);

        WorkloadInventoryFilterBean filterBean = new WorkloadInventoryFilterBean();
        filterBean.setProviders(new HashSet<>(Arrays.asList(provider1, provider2)));
        filterBean.setClusters(new HashSet<>(Arrays.asList(cluster1, cluster2)));
        filterBean.setDatacenters(new HashSet<>(Arrays.asList(datacenter1, datacenter2)));;
        filterBean.setVmNames(new HashSet<>(Arrays.asList(vmName1, vmName2)));;
        filterBean.setOsNames(new HashSet<>(Arrays.asList(osName1, osName2)));;
        filterBean.setWorkloads(new HashSet<>(Arrays.asList(workload1, workload2)));
        filterBean.setRecommendedTargetsIMS(new HashSet<>(Arrays.asList(recommendedTarget1, recommendedTarget2)));
        filterBean.setFlagsIMS(new HashSet<>(Arrays.asList(flag1, flag2)));
        filterBean.setComplexities(new HashSet<>(Arrays.asList(complexity1, complexity2)));

        verify(workloadInventoryReportService).findByAnalysisOwnerAndAnalysisId(analysisModel.getOwner(), analysisModel.getId(), sortBean, filterBean); // check right params are use
        Assert.assertTrue(response.getHeaders().get("Content-Type").contains("text/csv"));
        Assert.assertTrue(response.getHeaders().get("Content-Disposition").contains("attachment;filename=workloadInventory_1.csv"));
        assertThat(response).isNotNull();
        assertThat(response.getBody()).isNotNull();

        String[] rows = response.getBody().split("\n");
        assertThat(rows.length).isEqualTo(1);
        assertThat(response.getBody()).contains("Provider,Datacenter,Cluster,VM name,OS type,Operating system description,Disk space,Memory,CPU cores,Workload,Effort,Recommended targets,Flags IMS,Product,Version,HostName");
        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestReportIdWorkloadInventory_IdParamGiven_ShouldCallFindByAnalysisIdAndReturnAvailableFilters() throws Exception {
        //Given
        Long one = 1L;
        when(analysisService.findByOwnerAndId("mrizzi@redhat.com", one)).thenReturn(new AnalysisModel());

        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("workload-inventory-report-available-filters");
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", one);
        HttpHeaders headers = new HttpHeaders();
        headers.add("whatever", "this header should not be copied");
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange(camel_context + "report/{id}/workload-inventory/available-filters" , HttpMethod.GET, entity, String.class, variables);

        //Then
        verify(analysisService).findByOwnerAndId("mrizzi@redhat.com", one);
        verify(workloadInventoryReportService).findAvailableFiltersByAnalysisId(one);
        assertThat(response).isNotNull();
        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestReportIdWorkloadSummary_IdParamGiven_ShouldCallFindByAnalysisOwnerAndAnalysisId() throws Exception {
        //Given

        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("workload-summary-report-get");
        Map<String, Object> variables = new HashMap<>();
        Long analysisId = 11L;
        variables.put("id", analysisId);
        HttpHeaders headers = new HttpHeaders();
        headers.add("whatever", "this header should not be copied");
        headers.add(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange(camel_context + "report/{id}/workload-summary" , HttpMethod.GET, entity, String.class, variables);

        //Then
        verify(workloadSummaryReportService).findByAnalysisOwnerAndAnalysisId("mrizzi@redhat.com", analysisId);
        Assert.assertNull(response.getHeaders().get("whatever"));
        assertThat(response).isNotNull();
        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestReportIdWorkloadSummaryWorkloads_IdParamGiven_ShouldCallFindByAnalysisId() throws Exception {
        //Given

        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("to-paginationBean");
        camelContext.startRoute("to-sortBean");
        camelContext.startRoute("workload-summary-workloads-report-get");
        Map<String, Object> variables = new HashMap<>();
        Long one = 1L;
        variables.put("id", one);

        HttpHeaders headers = new HttpHeaders();
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());
        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        ResponseEntity<String> response = restTemplate.exchange(camel_context + "report/{id}/workload-summary/workloads", HttpMethod.GET, entity, String.class, variables);

        //Then
        PageBean pageBean = new PageBean(0, 10);
        SortBean sortBean = new SortBean("id", false);

        verify(workloadService).findByReportAnalysisOwnerAndReportAnalysisId("mrizzi@redhat.com", one, pageBean, sortBean);
        assertThat(response).isNotNull();
        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestReportIdWorkloadSummaryWorkloads_IdParamGiven_PaginationGiven_ShouldCallFindByAnalysisId() throws Exception {
        //Given

        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("to-paginationBean");
        camelContext.startRoute("to-sortBean");
        camelContext.startRoute("workload-summary-workloads-report-get");
        Map<String, Object> variables = new HashMap<>();
        Long one = 1L;
        variables.put("id", one);
        int page = 2;
        variables.put("page", page);
        int size = 3;
        variables.put("size", size);

        HttpHeaders headers = new HttpHeaders();
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());
        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        ResponseEntity<String> response = restTemplate.exchange(camel_context + "report/{id}/workload-summary/workloads?page={page}&size={size}", HttpMethod.GET, entity, String.class, variables);

        //Then
        PageBean pageBean = new PageBean(page, size);
        SortBean sortBean = new SortBean("id", false);

        verify(workloadService).findByReportAnalysisOwnerAndReportAnalysisId("mrizzi@redhat.com", one, pageBean, sortBean);
        assertThat(response).isNotNull();
        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestReportIdWorkloadSummaryWorkloads_IdParamGiven_SortGiven_ShouldCallFindByAnalysisId() throws Exception {
        //Given

        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("to-paginationBean");
        camelContext.startRoute("to-sortBean");
        camelContext.startRoute("workload-summary-workloads-report-get");
        Map<String, Object> variables = new HashMap<>();
        Long one = 1L;
        variables.put("id", one);
        String orderBy = "workload";
        variables.put("orderBy", orderBy);
        Boolean orderAsc = true;
        variables.put("orderAsc", orderAsc);

        HttpHeaders headers = new HttpHeaders();
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());
        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        ResponseEntity<String> response = restTemplate.exchange(camel_context + "report/{id}/workload-summary/workloads?orderBy={orderBy}&orderAsc={orderAsc}", HttpMethod.GET, entity, String.class, variables);

        //Then
        PageBean pageBean = new PageBean(0, 10);
        SortBean sortBean = new SortBean(orderBy, orderAsc);

        verify(workloadService).findByReportAnalysisOwnerAndReportAnalysisId("mrizzi@redhat.com", one, pageBean, sortBean);
        assertThat(response).isNotNull();
        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestReportIdWorkloadSummaryFlags_IdParamGiven_ShouldCallFindByAnalysisId() throws Exception {
        //Given

        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("to-paginationBean");
        camelContext.startRoute("to-sortBean");
        camelContext.startRoute("workload-summary-flags-report-get");
        Map<String, Object> variables = new HashMap<>();
        Long one = 1L;
        variables.put("id", one);

        HttpHeaders headers = new HttpHeaders();
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());
        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        ResponseEntity<String> response = restTemplate.exchange(camel_context + "report/{id}/workload-summary/flags", HttpMethod.GET, entity, String.class, variables);

        //Then
        PageBean pageBean = new PageBean(0, 10);
        SortBean sortBean = new SortBean("id", true);

        verify(flagService).findByReportAnalysisOwnerAndReportAnalysisId("mrizzi@redhat.com", one, pageBean, sortBean);
        assertThat(response).isNotNull();
        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestReportIdWorkloadSummaryFlags_IdParamGiven_PaginationGiven_ShouldCallFindByAnalysisId() throws Exception {
        //Given

        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("to-paginationBean");
        camelContext.startRoute("to-sortBean");
        camelContext.startRoute("workload-summary-flags-report-get");
        Map<String, Object> variables = new HashMap<>();
        Long one = 1L;
        variables.put("id", one);
        int page = 2;
        variables.put("page", page);
        int size = 3;
        variables.put("size", size);

        HttpHeaders headers = new HttpHeaders();
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());
        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        ResponseEntity<String> response = restTemplate.exchange(camel_context + "report/{id}/workload-summary/flags?page={page}&size={size}", HttpMethod.GET, entity, String.class, variables);

        //Then
        PageBean pageBean = new PageBean(page, size);
        SortBean sortBean = new SortBean("id", true);

        verify(flagService).findByReportAnalysisOwnerAndReportAnalysisId("mrizzi@redhat.com", one, pageBean, sortBean);
        assertThat(response).isNotNull();
        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestReportIdWorkloadSummaryFlags_IdParamGiven_SortGiven_ShouldCallFindByAnalysisId() throws Exception {
        //Given

        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("to-paginationBean");
        camelContext.startRoute("to-sortBean");
        camelContext.startRoute("workload-summary-flags-report-get");
        Map<String, Object> variables = new HashMap<>();
        Long one = 1L;
        variables.put("id", one);
        String orderBy = "workload";
        variables.put("orderBy", orderBy);
        Boolean orderAsc = true;
        variables.put("orderAsc", orderAsc);

        HttpHeaders headers = new HttpHeaders();
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());
        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        ResponseEntity<String> response = restTemplate.exchange(camel_context + "report/{id}/workload-summary/flags?orderBy={orderBy}&orderAsc={orderAsc}", HttpMethod.GET, entity, String.class, variables);

        //Then
        PageBean pageBean = new PageBean(0, 10);
        SortBean sortBean = new SortBean(orderBy, orderAsc);

        verify(flagService).findByReportAnalysisOwnerAndReportAnalysisId("mrizzi@redhat.com", one, pageBean, sortBean);
        assertThat(response).isNotNull();
        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestAdministrationCsv_ShouldCallGetMetrics() throws Exception {
        //Given

        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("administration-report-csv-aggregator");
        camelContext.startRoute("administration-metrics-model-to-csv");
        camelContext.startRoute("administration-report-csv-generator");
        camelContext.startRoute("administration-report-csv");

        HttpHeaders headers = new HttpHeaders();
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity("admin2@redhat.com"));
        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        ResponseEntity<String> response = restTemplate.exchange(camel_context + "administration/report/csv", HttpMethod.GET, entity, String.class);

        //Then
        verify(analysisService, times(2)).getAdministrationMetrics(any(), any());
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualByComparingTo(HttpStatus.OK);
        assertThat(response.getHeaders().get(CustomizedMultipartDataFormat.CONTENT_DISPOSITION)).isNotNull();
        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestPayload_AnalysisIdGiven_ShouldReturnAPayload() throws Exception {
        //Given

        camelContext.getRouteDefinition("report-payload-download").adviceWith(camelContext, new AdviceWithRouteBuilder() {
                @Override
                public void configure() throws Exception {
                    weaveById("pollEnrich").replace()
                            .setHeader(Exchange.HTTP_RESPONSE_CODE, simple("200"))
                            .setHeader("CamelAwsS3ContentDisposition", constant("attachment; filename=\"cloudforms-export-v1_0_0.json\""))
                            .setHeader("CamelAwsS3ContentType", constant("application/octet-stream"))
                            .setBody(exchange -> this.getClass().getClassLoader().getResourceAsStream("cloudforms-export-v1_0_0.json"));
                }
            });

        //When
        camelContext.start();
        camelContext.startRoute("report-payload-download");
        camelContext.startRoute("check-authenticated-request");
        camelContext.startRoute("add-username-header");

        AnalysisModel analysisModel = new AnalysisModel();
        analysisModel.setId(9L);
        analysisModel.setPayloadName("cloudforms-export-v1_0_0.json");
        analysisModel.setPayloadStorageId("http://www.google.com");
        doReturn(analysisModel).when(analysisService).findByOwnerAndId(any(), any());

        HttpHeaders headers = new HttpHeaders();
        headers.set("username", "testuser");
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());
        HttpEntity<String> entity = new HttpEntity<>("", headers);
        ResponseEntity<String> answer = restTemplate.exchange(camel_context + "report/15/payload", HttpMethod.GET, entity, String.class);

        //Then
        assertThat(answer.getBody()).isEqualToIgnoringCase(IOUtils.resourceToString("cloudforms-export-v1_0_0.json", StandardCharsets.UTF_8, this.getClass().getClassLoader()));
        assertThat(answer.getHeaders().get("Content-Disposition").get(0)).isEqualToIgnoringCase("attachment; filename=\"cloudforms-export-v1_0_0.json\"");

        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestPayloadDownloadLink_AnalysisIdGiven_ShouldReturnPayloadLinkURL() throws Exception {
        //Given
        camelContext.getRouteDefinition("get-s3-payload-link").adviceWith(camelContext, new AdviceWithRouteBuilder() {
            @Override
            public void configure() throws Exception {
                weaveById("aws-s3-get-download-link").replace()
                        .setHeader("CamelAwsS3DownloadLink", constant("https://myDownloadLink.s3.com"));
            }
        });

        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("report-payload-link");
        camelContext.startRoute("get-s3-payload-link");

        AnalysisModel analysisModel = new AnalysisModel();
        analysisModel.setId(1L);
        analysisModel.setPayloadName("cloudforms-export-v1_0_0.json");
        analysisModel.setPayloadStorageId("cddd46f6-a8c4-4be0-98d8-ea7c466cb4a2");
        doReturn(analysisModel).when(analysisService).findByOwnerAndId(any(), any());

        HttpHeaders headers = new HttpHeaders();
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());

        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> answer = restTemplate.exchange(camel_context + "report/" + analysisModel.getId() +"/payload-link", HttpMethod.GET, entity, String.class);

        //Then
        assertThat(answer.getBody()).isEqualTo("{\"filename\":\"cloudforms-export-v1_0_0.json\",\"downloadLink\":\"https://myDownloadLink.s3.com\"}");

        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestPayloadDownloadLink_UnexistingAnalysisIdGiven_ShouldReturnNotFoundError() throws Exception {
        //Given


        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("report-payload-link");
        camelContext.startRoute("get-s3-payload-link");

        doReturn(null).when(analysisService).findByOwnerAndId(any(), any());

        HttpHeaders headers = new HttpHeaders();
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());

        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> answer = restTemplate.exchange(camel_context + "report/1/payload-link", HttpMethod.GET, entity, String.class);

        //Then
        assertThat(answer.getStatusCodeValue()).isEqualTo(404);

        camelContext.stop();
    }

    @Test
    public void xmlRouteBuilder_RestPayloadDownloadLink_AnalysisWithoutPayloadStorageIdGiven_ShouldReturnEmptyPayloadLinkURL() throws Exception {
        //Given

        //When
        camelContext.start();
        TestUtil.startUsernameRoutes(camelContext);
        camelContext.startRoute("report-payload-link");
        camelContext.startRoute("get-s3-payload-link");

        AnalysisModel analysisModel = new AnalysisModel();
        analysisModel.setId(1L);
        analysisModel.setPayloadName("cloudforms-export-v1_0_0.json");
        analysisModel.setPayloadStorageId(null);
        doReturn(analysisModel).when(analysisService).findByOwnerAndId(any(), any());

        HttpHeaders headers = new HttpHeaders();
        headers.set(TestUtil.HEADER_RH_IDENTITY, TestUtil.getBase64RHIdentity());

        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> answer = restTemplate.exchange(camel_context + "report/" + analysisModel.getId() +"/payload-link", HttpMethod.GET, entity, String.class);

        //Then
        assertThat(answer.getBody()).isEqualTo("{\"filename\":\"cloudforms-export-v1_0_0.json\",\"downloadLink\":null}");

        camelContext.stop();
    }

}
