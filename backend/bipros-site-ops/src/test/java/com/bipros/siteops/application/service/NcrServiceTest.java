package com.bipros.siteops.application.service;

import com.bipros.security.application.service.CurrentUserService;
import com.bipros.siteops.application.dto.CreateNcrRequest;
import com.bipros.siteops.application.dto.NcrResponse;
import com.bipros.siteops.domain.model.Ncr;
import com.bipros.siteops.domain.model.NcrSourceType;
import com.bipros.siteops.domain.repository.NcrRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NcrServiceTest {

    @Mock NcrRepository ncrRepository;
    @Mock CurrentUserService securityContext;
    NcrService service;
    final UUID projectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new NcrService(ncrRepository, securityContext);
        lenient().when(ncrRepository.save(any(Ncr.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(ncrRepository.countByProjectId(projectId)).thenReturn(0L);
        lenient().when(securityContext.getCurrentUserId()).thenReturn(UUID.randomUUID());
    }

    @Test
    void create_defaultsSourceTypeManual_whenNull() {
        var req = new CreateNcrRequest("Title", "Desc", null, null, null, null, null, null);
        NcrResponse res = service.create(projectId, req);
        assertThat(res.sourceType()).isEqualTo(NcrSourceType.MANUAL);
        assertThat(res.sourceRefId()).isNull();
        assertThat(res.activityId()).isNull();
    }

    @Test
    void create_persistsQcSourceLink() {
        UUID testItemId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        var req = new CreateNcrRequest("QC FAIL: CBR @ 45+000", "result 7.6 vs spec 8",
                null, null, null, NcrSourceType.QC_TEST_FAIL, testItemId, activityId);
        ArgumentCaptor<Ncr> captor = ArgumentCaptor.forClass(Ncr.class);
        service.create(projectId, req);
        verify(ncrRepository).save(captor.capture());
        Ncr saved = captor.getValue();
        assertThat(saved.getSourceType()).isEqualTo(NcrSourceType.QC_TEST_FAIL);
        assertThat(saved.getSourceRefId()).isEqualTo(testItemId);
        assertThat(saved.getActivityId()).isEqualTo(activityId);
    }
}
