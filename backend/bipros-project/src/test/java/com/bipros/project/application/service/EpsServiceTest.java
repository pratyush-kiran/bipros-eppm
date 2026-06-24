package com.bipros.project.application.service;

import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.project.application.dto.CreateEpsNodeRequest;
import com.bipros.project.application.dto.UpdateEpsNodeRequest;
import com.bipros.project.domain.model.EpsNode;
import com.bipros.project.domain.model.Project;
import com.bipros.common.util.AuditService;
import com.bipros.project.domain.repository.EpsNodeRepository;
import com.bipros.project.domain.repository.ProjectRepository;
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
@DisplayName("EpsService Tests")
class EpsServiceTest {

  @Mock
  private EpsNodeRepository epsNodeRepository;

  @Mock
  private ProjectRepository projectRepository;

  @Mock
  private AuditService auditService;

  private EpsService epsService;

  @BeforeEach
  void setUp() {
    epsService = new EpsService(epsNodeRepository, projectRepository, auditService);
  }

  @Nested
  @DisplayName("Create node")
  class CreateNodeTests {

    @Test
    @DisplayName("creating node with unique code succeeds")
    void createNodeWithUniqueCodeSucceeds() {
      UUID nodeId = UUID.randomUUID();
      UUID parentId = UUID.randomUUID();
      String code = "EPS-001";
      String name = "Enterprise Division";

      when(epsNodeRepository.existsByCode(code)).thenReturn(false);

      // Mock save to return saved node
      EpsNode savedNode = new EpsNode();
      savedNode.setId(nodeId);
      savedNode.setCode(code);
      savedNode.setName(name);
      savedNode.setParentId(parentId);

      when(epsNodeRepository.save(any())).thenAnswer(inv -> {
        EpsNode node = inv.getArgument(0);
        node.setId(nodeId);
        return node;
      });
      when(epsNodeRepository.findByParentIdOrderBySortOrder(nodeId)).thenReturn(new ArrayList<>());

      var result = epsService.createNode(new CreateEpsNodeRequest(code, name, parentId, null));

      assertNotNull(result);
      assertEquals(code, result.code());
      assertEquals(name, result.name());
      verify(epsNodeRepository).save(any());
    }

    @Test
    @DisplayName("creating node with duplicate code throws BusinessRuleException")
    void createNodeWithDuplicateCodeThrows() {
      String code = "EPS-001";
      String name = "Enterprise Division";
      UUID parentId = UUID.randomUUID();

      when(epsNodeRepository.existsByCode(code)).thenReturn(true);

      BusinessRuleException exception = assertThrows(
          BusinessRuleException.class,
          () -> epsService.createNode(new CreateEpsNodeRequest(code, name, parentId, null))
      );

      assertEquals("EPS_CODE_DUPLICATE", exception.getRuleCode());
      assertTrue(exception.getMessage().contains("code") || exception.getMessage().contains("already exists"));
      verify(epsNodeRepository, never()).save(any());
    }
  }

  @Nested
  @DisplayName("Delete node")
  class DeleteNodeTests {

    @Test
    @DisplayName("deleting node without children or projects succeeds")
    void deleteNodeWithoutChildrenSucceeds() {
      UUID nodeId = UUID.randomUUID();

      EpsNode node = new EpsNode();
      node.setId(nodeId);
      node.setCode("EPS-001");
      node.setName("Division");

      when(epsNodeRepository.findById(nodeId)).thenReturn(Optional.of(node));
      when(epsNodeRepository.findByParentIdOrderBySortOrder(nodeId)).thenReturn(new ArrayList<>());
      when(projectRepository.findByEpsNodeId(nodeId)).thenReturn(new ArrayList<>());

      epsService.deleteNode(nodeId);

      verify(epsNodeRepository).delete(node);
    }

    @Test
    @DisplayName("deleting node with children throws BusinessRuleException")
    void deleteNodeWithChildrenThrows() {
      UUID nodeId = UUID.randomUUID();
      UUID childId = UUID.randomUUID();

      EpsNode node = new EpsNode();
      node.setId(nodeId);
      node.setCode("EPS-001");
      node.setName("Division");

      EpsNode child = new EpsNode();
      child.setId(childId);
      child.setCode("EPS-001-A");
      child.setName("Subdivision");
      child.setParentId(nodeId);

      when(epsNodeRepository.findById(nodeId)).thenReturn(Optional.of(node));
      when(epsNodeRepository.findByParentIdOrderBySortOrder(nodeId)).thenReturn(List.of(child));

      BusinessRuleException exception = assertThrows(
          BusinessRuleException.class,
          () -> epsService.deleteNode(nodeId)
      );

      assertEquals("EPS_HAS_CHILDREN", exception.getRuleCode());
      assertTrue(exception.getMessage().contains("child") || exception.getMessage().contains("children"));
      assertTrue(exception.getMessage().contains("EPS-001"), "message should name the node");
      assertTrue(exception.getMessage().contains("1 child node"), "message should include the count");
      verify(epsNodeRepository, never()).delete(any());
    }

    @Test
    @DisplayName("deleting node with projects throws BusinessRuleException")
    void deleteNodeWithProjectsThrows() {
      UUID nodeId = UUID.randomUUID();
      UUID projectId = UUID.randomUUID();

      EpsNode node = new EpsNode();
      node.setId(nodeId);
      node.setCode("EPS-001");
      node.setName("Division");

      Project project = new Project();
      project.setId(projectId);
      project.setEpsNodeId(nodeId);

      when(epsNodeRepository.findById(nodeId)).thenReturn(Optional.of(node));
      when(epsNodeRepository.findByParentIdOrderBySortOrder(nodeId)).thenReturn(new ArrayList<>());
      when(projectRepository.findByEpsNodeId(nodeId)).thenReturn(List.of(project));

      BusinessRuleException exception = assertThrows(
          BusinessRuleException.class,
          () -> epsService.deleteNode(nodeId)
      );

      assertEquals("EPS_HAS_PROJECTS", exception.getRuleCode());
      assertTrue(exception.getMessage().contains("project"));
      assertTrue(exception.getMessage().contains("EPS-001"), "message should name the node");
      assertTrue(exception.getMessage().contains("1 project"), "message should include the count");
      verify(epsNodeRepository, never()).delete(any());
    }

    @Test
    @DisplayName("deleting non-existent node throws ResourceNotFoundException")
    void deleteNonExistentNodeThrows() {
      UUID nodeId = UUID.randomUUID();

      when(epsNodeRepository.findById(nodeId)).thenReturn(Optional.empty());

      assertThrows(
          ResourceNotFoundException.class,
          () -> epsService.deleteNode(nodeId)
      );

      verify(epsNodeRepository, never()).delete(any());
    }
  }

  @Nested
  @DisplayName("Update node")
  class UpdateNodeTests {

    @Test
    @DisplayName("updating node name succeeds")
    void updateNodeNameSucceeds() {
      UUID nodeId = UUID.randomUUID();
      String newName = "Updated Name";

      EpsNode node = new EpsNode();
      node.setId(nodeId);
      node.setCode("EPS-001");
      node.setName("Old Name");

      when(epsNodeRepository.findById(nodeId)).thenReturn(Optional.of(node));
      when(epsNodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
      when(epsNodeRepository.findByParentIdOrderBySortOrder(nodeId)).thenReturn(new ArrayList<>());

      var result = epsService.updateNode(nodeId, new UpdateEpsNodeRequest(newName, null, null));

      assertEquals(newName, result.name());
      verify(epsNodeRepository).save(any());
    }
  }
}
