package com.bipros.project.application.service;

import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.util.AuditService;
import com.bipros.project.domain.model.ObsNode;
import com.bipros.project.domain.repository.ObsNodeRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.project.domain.repository.WbsNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ObsService Tests")
class ObsServiceTest {

  @Mock
  private ObsNodeRepository obsNodeRepository;

  @Mock
  private ProjectRepository projectRepository;

  @Mock
  private WbsNodeRepository wbsNodeRepository;

  @Mock
  private AuditService auditService;

  private ObsService obsService;

  @BeforeEach
  void setUp() {
    obsService = new ObsService(obsNodeRepository, projectRepository, wbsNodeRepository, auditService);
  }

  private static ObsNode node(UUID id, String code, String name) {
    ObsNode n = new ObsNode();
    n.setId(id);
    n.setCode(code);
    n.setName(name);
    return n;
  }

  @Nested
  @DisplayName("Delete node")
  class DeleteNodeTests {

    @Test
    @DisplayName("deleting a clean leaf (no children, no projects, no WBS) succeeds")
    void deleteCleanLeafSucceeds() {
      UUID nodeId = UUID.randomUUID();
      ObsNode node = node(nodeId, "OBS-001", "Team");

      when(obsNodeRepository.findById(nodeId)).thenReturn(Optional.of(node));
      when(obsNodeRepository.findByParentIdOrderBySortOrder(nodeId)).thenReturn(new ArrayList<>());
      when(projectRepository.countByObsNodeId(nodeId)).thenReturn(0L);
      when(wbsNodeRepository.countByObsNodeId(nodeId)).thenReturn(0L);

      obsService.deleteNode(nodeId);

      verify(obsNodeRepository).delete(node);
    }

    @Test
    @DisplayName("deleting a node with children throws OBS_HAS_CHILDREN and names the node")
    void deleteNodeWithChildrenThrows() {
      UUID nodeId = UUID.randomUUID();
      ObsNode node = node(nodeId, "OBS-001", "Team");
      ObsNode child = node(UUID.randomUUID(), "OBS-001-A", "Sub-team");
      child.setParentId(nodeId);

      when(obsNodeRepository.findById(nodeId)).thenReturn(Optional.of(node));
      when(obsNodeRepository.findByParentIdOrderBySortOrder(nodeId)).thenReturn(List.of(child));

      BusinessRuleException ex = assertThrows(
          BusinessRuleException.class,
          () -> obsService.deleteNode(nodeId)
      );

      assertEquals("OBS_HAS_CHILDREN", ex.getRuleCode());
      assertTrue(ex.getMessage().contains("child"));
      assertTrue(ex.getMessage().contains("OBS-001"), "message should name the node");
      verify(obsNodeRepository, never()).delete(any());
    }

    @Test
    @DisplayName("deleting a node assigned to a project throws OBS_IN_USE")
    void deleteNodeUsedByProjectThrows() {
      UUID nodeId = UUID.randomUUID();
      ObsNode node = node(nodeId, "OBS-001", "Team");

      when(obsNodeRepository.findById(nodeId)).thenReturn(Optional.of(node));
      when(obsNodeRepository.findByParentIdOrderBySortOrder(nodeId)).thenReturn(new ArrayList<>());
      when(projectRepository.countByObsNodeId(nodeId)).thenReturn(2L);
      when(wbsNodeRepository.countByObsNodeId(nodeId)).thenReturn(0L);

      BusinessRuleException ex = assertThrows(
          BusinessRuleException.class,
          () -> obsService.deleteNode(nodeId)
      );

      assertEquals("OBS_IN_USE", ex.getRuleCode());
      assertTrue(ex.getMessage().contains("project"));
      verify(obsNodeRepository, never()).delete(any());
    }

    @Test
    @DisplayName("deleting a node referenced by a WBS element (no project) throws OBS_IN_USE")
    void deleteNodeUsedByWbsThrows() {
      UUID nodeId = UUID.randomUUID();
      ObsNode node = node(nodeId, "OBS-001", "Team");

      when(obsNodeRepository.findById(nodeId)).thenReturn(Optional.of(node));
      when(obsNodeRepository.findByParentIdOrderBySortOrder(nodeId)).thenReturn(new ArrayList<>());
      when(projectRepository.countByObsNodeId(nodeId)).thenReturn(0L);
      when(wbsNodeRepository.countByObsNodeId(nodeId)).thenReturn(3L);

      BusinessRuleException ex = assertThrows(
          BusinessRuleException.class,
          () -> obsService.deleteNode(nodeId)
      );

      assertEquals("OBS_IN_USE", ex.getRuleCode());
      assertTrue(ex.getMessage().contains("WBS"));
      verify(obsNodeRepository, never()).delete(any());
    }

    @Test
    @DisplayName("deleting a non-existent node throws ResourceNotFoundException")
    void deleteNonExistentNodeThrows() {
      UUID nodeId = UUID.randomUUID();

      when(obsNodeRepository.findById(nodeId)).thenReturn(Optional.empty());

      assertThrows(
          ResourceNotFoundException.class,
          () -> obsService.deleteNode(nodeId)
      );

      verify(obsNodeRepository, never()).delete(any());
    }
  }
}
