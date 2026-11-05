package com.example.server.service;
import com.example.server.dto.*;
import com.example.server.mapper.DocumentParseStateMapper;
import com.example.server.utils.MinioUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class StructuredDocumentServiceTest {
    @Test void resumesSavedParsingWithoutCallingExternalParser() throws Exception {
        var legacy=mock(DocumentParsingService.class);var mineru=mock(MineruDocumentParser.class);
        var states=mock(DocumentParseStateMapper.class);var json=new ObjectMapper();
        var context=new DocumentContext(1L,"source","title",DocumentType.PDF,List.of());
        when(states.load(1L)).thenReturn(json.writeValueAsString(context));
        var service=new StructuredDocumentService(legacy,mineru,states,json,mock(MinioUtils.class),Runnable::run,
                "mineru","","https://example.com","vision",2,mock(com.example.server.ratelimit.RequestQuotaService.class));
        assertThat(service.parse(1L,"source","test.pdf",Path.of("missing.pdf"))).isEqualTo(context);
        verifyNoInteractions(legacy,mineru);
    }
    @Test void missingVisionCredentialPreservesOriginalImagesAndReportsPending() throws Exception {
        var service=new StructuredDocumentService(mock(DocumentParsingService.class),mock(MineruDocumentParser.class),
                mock(DocumentParseStateMapper.class),new ObjectMapper(),mock(MinioUtils.class),Runnable::run,
                "mineru","","https://example.com","vision",2,mock(com.example.server.ratelimit.RequestQuotaService.class));
        var segment=new DocumentSegment("image-1","chapter",1,null,null,null,null,"caption",List.of("image.png"),List.of());
        var context=new DocumentContext(1L,"source","title",DocumentType.PDF,List.of(segment));
        assertThat(service.enrich(context)).isEqualTo(context);
        assertThat(service.hasPendingImages(context)).isTrue();
    }
}
