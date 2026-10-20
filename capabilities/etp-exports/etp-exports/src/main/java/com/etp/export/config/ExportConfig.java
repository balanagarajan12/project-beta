package com.etp.export.config;

import java.net.http.HttpClient;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.etp.export.generator.CsvFileGenerator;
import com.etp.export.generator.FileGenerator;
import com.etp.export.generator.FileGeneratorFactory;
import com.etp.export.generator.XlsxFileGenerator;
import com.etp.export.service.DataExtractor;

import tools.jackson.databind.json.JsonMapper;

@Configuration
public class ExportConfig {

    /** Dedicated RestClient for outbound export calls. Redirects are not followed so the host allow-list can't be bypassed. */
    @Bean
    public RestClient exportRestClient(ExportProperties props) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(props.readTimeout());
        return RestClient.builder().requestFactory(factory).build();
    }

    @Bean
    public DataExtractor dataExtractor(JsonMapper jsonMapper) {
        // nested arrays of objects are written into a single cell as JSON
        return new DataExtractor(jsonMapper::writeValueAsString);
    }

    @Bean
    public CsvFileGenerator csvFileGenerator(ExportProperties props) {
        return new CsvFileGenerator(props.csvUtf8Bom());
    }

    @Bean
    public XlsxFileGenerator xlsxFileGenerator() {
        return new XlsxFileGenerator();
    }

    @Bean
    public FileGeneratorFactory fileGeneratorFactory(List<FileGenerator> generators) {
        return new FileGeneratorFactory(generators);
    }
}
