package com.bipros.resource.application.service.role;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.common.exception.BusinessRuleException;
import com.bipros.resource.application.dto.role.RoleAssignmentRequest;
import com.bipros.resource.application.dto.role.RoleAssignmentResponse;
import com.bipros.resource.domain.model.ResourceAssignment;
import com.bipros.resource.domain.model.ResourceRole;
import com.bipros.resource.domain.model.ResourceType;
import com.bipros.resource.domain.repository.GradeMasterRepository;
import com.bipros.resource.domain.repository.ManpowerCategoryMasterRepository;
import com.bipros.resource.domain.repository.ResourceAssignmentRepository;
import com.bipros.resource.domain.repository.ResourceRoleRepository;
import com.bipros.resource.domain.repository.role.EquipmentRoleVariantRepository;
import com.bipros.resource.domain.repository.role.ManpowerRoleRateRepository;
import com.bipros.resource.domain.repository.role.MaterialRoleVariantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleAssignmentServiceTest {

    @Mock private ResourceAssignmentRepository assignmentRepo;
    @Mock private ResourceRoleRepository roleRepo;
    @Mock private ActivityRepository activityRepo;
    @Mock private ManpowerRoleRateRepository manpowerRepo;
    @Mock private EquipmentRoleVariantRepository equipmentRepo;
    @Mock private MaterialRoleVariantRepository materialRepo;
    @Mock private ManpowerCategoryMasterRepository categoryRepo;
    @Mock private GradeMasterRepository gradeRepo;
    @Mock private RoleRateResolver rateResolver;
    @InjectMocks private RoleAssignmentService service;

    @Test
    void manpowerPlannedUnitsEqualsHeadcountWithoutDurationMultiplication() {
        UUID projectId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();

        // Activity has 71-day duration; under Option B this must NOT multiply.
        Activity activity = new Activity();
        activity.setId(activityId);
        activity.setOriginalDuration(71.0);

        ResourceType labor = new ResourceType();
        labor.setCode("MANPOWER");
        ResourceRole role = new ResourceRole();
        role.setId(roleId);
        role.setResourceType(labor);

        when(activityRepo.findById(activityId)).thenReturn(Optional.of(activity));
        when(roleRepo.findById(roleId)).thenReturn(Optional.of(role));
        when(rateResolver.resolveRate(projectId, "MANPOWER", variantId))
                .thenReturn(new BigDecimal("1.20"));
        when(rateResolver.resolveUnit("MANPOWER", variantId)).thenReturn("Hr");
        when(assignmentRepo
                .findFirstByActivityIdAndRoleIdAndManpowerRoleRateId(activityId, roleId, variantId))
                .thenReturn(Optional.empty());
        when(assignmentRepo.save(any(ResourceAssignment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        RoleAssignmentRequest req = new RoleAssignmentRequest(
                activityId, roleId, variantId, null, null,
                50, null, null,    // headcount=50, duration null, quantity null
                null, null, null);

        RoleAssignmentResponse resp = service.createRoleAssignment(projectId, req);

        // Option B: plannedUnits is the admin's typed headcount, NOT headcount × duration.
        ArgumentCaptor<ResourceAssignment> captor = ArgumentCaptor.forClass(ResourceAssignment.class);
        org.mockito.Mockito.verify(assignmentRepo).save(captor.capture());
        ResourceAssignment saved = captor.getValue();

        assertThat(saved.getPlannedUnits()).isEqualTo(50.0);    // NOT 3550.0
        assertThat(saved.getPlannedCost()).isEqualByComparingTo(new BigDecimal("60.00"));
        assertThat(saved.getHeadcount()).isEqualTo(50);
    }

    // ===== Deployment guard: a row with DPR-deployed actuals can't be deleted, reduced
    //       below its actuals, or have its role/variant identity changed. =====

    private static ResourceRole manpowerRole(UUID roleId) {
        ResourceType labor = new ResourceType();
        labor.setCode("MANPOWER");
        ResourceRole role = new ResourceRole();
        role.setId(roleId);
        role.setResourceType(labor);
        return role;
    }

    @Test
    void deleteRejectsWhenResourceAlreadyDeployed() {
        UUID id = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        ResourceAssignment a = ResourceAssignment.builder()
                .activityId(activityId).actualUnits(16.0).build();
        a.setId(id);
        when(assignmentRepo.findById(id)).thenReturn(Optional.of(a));
        // Activity not found -> editable (no lock), so we reach the deployment guard.
        when(activityRepo.findById(activityId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteRoleAssignment(id))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("RESOURCE_DEPLOYED_DELETE"))
                .hasMessageContaining("16");
        verify(assignmentRepo, never()).delete(any());
    }

    @Test
    void deleteAllowedWhenNoActualsDeployed() {
        UUID id = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        ResourceAssignment a = ResourceAssignment.builder()
                .activityId(activityId).actualUnits(0.0).build();
        a.setId(id);
        when(assignmentRepo.findById(id)).thenReturn(Optional.of(a));
        when(activityRepo.findById(activityId)).thenReturn(Optional.empty());

        service.deleteRoleAssignment(id);

        verify(assignmentRepo).delete(a);
    }

    @Test
    void updateRejectsReducingPlannedBelowActual() {
        UUID projectId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();

        ResourceAssignment a = ResourceAssignment.builder()
                .activityId(activityId).projectId(projectId)
                .roleId(roleId).manpowerRoleRateId(variantId)
                .actualUnits(6.0).build();
        a.setId(id);

        when(assignmentRepo.findById(id)).thenReturn(Optional.of(a));
        when(activityRepo.findById(activityId)).thenReturn(Optional.empty());
        when(roleRepo.findById(roleId)).thenReturn(Optional.of(manpowerRole(roleId)));
        when(rateResolver.resolveRate(projectId, "MANPOWER", variantId)).thenReturn(new BigDecimal("1.0"));
        when(rateResolver.resolveUnit("MANPOWER", variantId)).thenReturn("Hr");

        RoleAssignmentRequest req = new RoleAssignmentRequest(
                activityId, roleId, variantId, null, null, 5, null, null, null, null, null);

        assertThatThrownBy(() -> service.updateRoleAssignment(id, req))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("RESOURCE_DEPLOYED_REDUCE"))
                .hasMessageContaining("6");
        verify(assignmentRepo, never()).save(any());
    }

    @Test
    void updateAllowsIncreasingPlannedWhenDeployed() {
        UUID projectId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();

        ResourceAssignment a = ResourceAssignment.builder()
                .activityId(activityId).projectId(projectId)
                .roleId(roleId).manpowerRoleRateId(variantId)
                .actualUnits(6.0).build();
        a.setId(id);

        when(assignmentRepo.findById(id)).thenReturn(Optional.of(a));
        when(activityRepo.findById(activityId)).thenReturn(Optional.empty());
        when(roleRepo.findById(roleId)).thenReturn(Optional.of(manpowerRole(roleId)));
        when(rateResolver.resolveRate(projectId, "MANPOWER", variantId)).thenReturn(new BigDecimal("1.0"));
        when(rateResolver.resolveUnit("MANPOWER", variantId)).thenReturn("Hr");
        when(manpowerRepo.findById(variantId)).thenReturn(Optional.empty());
        when(assignmentRepo.save(any(ResourceAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        RoleAssignmentRequest req = new RoleAssignmentRequest(
                activityId, roleId, variantId, null, null, 10, null, null, null, null, null);

        service.updateRoleAssignment(id, req);

        verify(assignmentRepo).save(any(ResourceAssignment.class));
        assertThat(a.getPlannedUnits()).isEqualTo(10.0);
    }

    @Test
    void updateAllowsEqualToActualWhenDeployed() {
        UUID projectId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();

        ResourceAssignment a = ResourceAssignment.builder()
                .activityId(activityId).projectId(projectId)
                .roleId(roleId).manpowerRoleRateId(variantId)
                .actualUnits(6.0).build();
        a.setId(id);

        when(assignmentRepo.findById(id)).thenReturn(Optional.of(a));
        when(activityRepo.findById(activityId)).thenReturn(Optional.empty());
        when(roleRepo.findById(roleId)).thenReturn(Optional.of(manpowerRole(roleId)));
        when(rateResolver.resolveRate(projectId, "MANPOWER", variantId)).thenReturn(new BigDecimal("1.0"));
        when(rateResolver.resolveUnit("MANPOWER", variantId)).thenReturn("Hr");
        when(manpowerRepo.findById(variantId)).thenReturn(Optional.empty());
        when(assignmentRepo.save(any(ResourceAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        RoleAssignmentRequest req = new RoleAssignmentRequest(
                activityId, roleId, variantId, null, null, 6, null, null, null, null, null);

        service.updateRoleAssignment(id, req);

        verify(assignmentRepo).save(any(ResourceAssignment.class));
        assertThat(a.getPlannedUnits()).isEqualTo(6.0);
    }

    @Test
    void updateRejectsRoleOrVariantChangeWhenDeployed() {
        UUID projectId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID oldVariant = UUID.randomUUID();
        UUID newVariant = UUID.randomUUID();

        ResourceAssignment a = ResourceAssignment.builder()
                .activityId(activityId).projectId(projectId)
                .roleId(roleId).manpowerRoleRateId(oldVariant)
                .actualUnits(6.0).build();
        a.setId(id);

        when(assignmentRepo.findById(id)).thenReturn(Optional.of(a));
        when(activityRepo.findById(activityId)).thenReturn(Optional.empty());
        when(roleRepo.findById(roleId)).thenReturn(Optional.of(manpowerRole(roleId)));
        when(rateResolver.resolveRate(projectId, "MANPOWER", newVariant)).thenReturn(new BigDecimal("1.0"));
        when(rateResolver.resolveUnit("MANPOWER", newVariant)).thenReturn("Hr");

        // Not reducing (10 >= 6), but the variant changes -> identity guard must fire.
        RoleAssignmentRequest req = new RoleAssignmentRequest(
                activityId, roleId, newVariant, null, null, 10, null, null, null, null, null);

        assertThatThrownBy(() -> service.updateRoleAssignment(id, req))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("RESOURCE_DEPLOYED_IDENTITY"))
                .hasMessageContaining("6");
        verify(assignmentRepo, never()).save(any());
    }

    @Test
    void updateAllowsReducingWhenNoActuals() {
        UUID projectId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();

        ResourceAssignment a = ResourceAssignment.builder()
                .activityId(activityId).projectId(projectId)
                .roleId(roleId).manpowerRoleRateId(variantId)
                .actualUnits(0.0).build();
        a.setId(id);

        when(assignmentRepo.findById(id)).thenReturn(Optional.of(a));
        when(activityRepo.findById(activityId)).thenReturn(Optional.empty());
        when(roleRepo.findById(roleId)).thenReturn(Optional.of(manpowerRole(roleId)));
        when(rateResolver.resolveRate(projectId, "MANPOWER", variantId)).thenReturn(new BigDecimal("1.0"));
        when(rateResolver.resolveUnit("MANPOWER", variantId)).thenReturn("Hr");
        when(manpowerRepo.findById(variantId)).thenReturn(Optional.empty());
        when(assignmentRepo.save(any(ResourceAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        RoleAssignmentRequest req = new RoleAssignmentRequest(
                activityId, roleId, variantId, null, null, 1, null, null, null, null, null);

        service.updateRoleAssignment(id, req);

        verify(assignmentRepo).save(any(ResourceAssignment.class));
        assertThat(a.getPlannedUnits()).isEqualTo(1.0);
    }
}
