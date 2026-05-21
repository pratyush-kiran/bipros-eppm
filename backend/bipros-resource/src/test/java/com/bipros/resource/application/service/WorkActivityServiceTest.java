package com.bipros.resource.application.service;

import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.util.AuditService;
import com.bipros.resource.application.dto.CreateWorkActivityRequest;
import com.bipros.resource.application.dto.WorkActivityResponse;
import com.bipros.resource.domain.model.WorkActivity;
import com.bipros.resource.domain.repository.ProductivityNormRepository;
import com.bipros.resource.domain.repository.WorkActivityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkActivityService")
class WorkActivityServiceTest {

  @Mock private WorkActivityRepository repository;
  @Mock private ProductivityNormRepository normRepository;
  @Mock private AuditService auditService;

  private WorkActivityService service;

  @BeforeEach
  void setUp() {
    service = new WorkActivityService(repository, normRepository, auditService);
  }

  @Nested
  @DisplayName("create")
  class CreateTests {

    @Test
    @DisplayName("derives code from name when blank, slug-cased and uppercased")
    void derivesCodeFromName() {
      when(repository.findByCode("CLEARING_GRUBBING")).thenReturn(Optional.empty());
      when(repository.save(any())).thenAnswer(inv -> {
        WorkActivity wa = inv.getArgument(0);
        wa.setId(UUID.randomUUID());
        return wa;
      });

      WorkActivityResponse r = service.create(new CreateWorkActivityRequest(
          null, "Clearing & Grubbing", "Sqm", null, null, 10, true, null));

      assertThat(r.code()).isEqualTo("CLEARING_GRUBBING");
      assertThat(r.name()).isEqualTo("Clearing & Grubbing");
      assertThat(r.defaultUnit()).isEqualTo("Sqm");
    }

    @Test
    @DisplayName("rejects duplicate code")
    void duplicateCodeRejected() {
      WorkActivity existing = WorkActivity.builder().code("CLEAR").build();
      when(repository.findByCode("CLEAR")).thenReturn(Optional.of(existing));

      assertThatThrownBy(() -> service.create(new CreateWorkActivityRequest(
          "clear", "Clear", "Sqm", null, null, null, true, null)))
          .isInstanceOf(BusinessRuleException.class)
          .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("defaults active to true when null")
    void defaultsActiveTrue() {
      when(repository.findByCode("X")).thenReturn(Optional.empty());
      when(repository.save(any())).thenAnswer(inv -> {
        WorkActivity wa = inv.getArgument(0);
        wa.setId(UUID.randomUUID());
        return wa;
      });

      WorkActivityResponse r = service.create(new CreateWorkActivityRequest(
          "x", "X", null, null, null, null, null, null));

      assertThat(r.active()).isTrue();
    }
  }

  @Nested
  @DisplayName("delete")
  class DeleteTests {

    @Test
    @DisplayName("blocks delete when norms still reference the activity")
    void blockedWhenInUse() {
      UUID id = UUID.randomUUID();
      WorkActivity wa = WorkActivity.builder().code("X").name("X").build();
      wa.setId(id);
      when(repository.findById(id)).thenReturn(Optional.of(wa));
      when(normRepository.countByWorkActivityId(id)).thenReturn(3L);

      assertThatThrownBy(() -> service.delete(id))
          .isInstanceOf(BusinessRuleException.class)
          .hasMessageContaining("3 productivity norm");
    }
  }

  @Nested
  @DisplayName("findOrCreateByName")
  class FindOrCreateTests {

    @Test
    @DisplayName("returns existing match when name found case-insensitively")
    void returnsExisting() {
      WorkActivity wa = WorkActivity.builder().code("EARTH").name("Earthwork").build();
      wa.setId(UUID.randomUUID());
      when(repository.findByNameIgnoreCase("Earthwork")).thenReturn(Optional.of(wa));

      WorkActivity result = service.findOrCreateByName("Earthwork", "Cum");
      assertThat(result).isSameAs(wa);
    }

    @Test
    @DisplayName("creates a new activity when none exists")
    void createsNew() {
      when(repository.findByNameIgnoreCase("Brand New")).thenReturn(Optional.empty());
      when(repository.findByCode("BRAND_NEW")).thenReturn(Optional.empty());
      when(repository.save(any())).thenAnswer(inv -> {
        WorkActivity wa = inv.getArgument(0);
        wa.setId(UUID.randomUUID());
        return wa;
      });

      WorkActivity result = service.findOrCreateByName("Brand New", "Sqm");
      assertThat(result.getCode()).isEqualTo("BRAND_NEW");
      assertThat(result.getDefaultUnit()).isEqualTo("Sqm");
    }
  }
}
