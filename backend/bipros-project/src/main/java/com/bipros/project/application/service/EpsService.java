package com.bipros.project.application.service;

import com.bipros.common.dto.PagedResponse;
import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.util.AuditService;
import com.bipros.project.application.dto.CreateEpsNodeRequest;
import com.bipros.project.application.dto.EpsNodeResponse;
import com.bipros.project.application.dto.NodeSearchResultResponse;
import com.bipros.project.application.dto.UpdateEpsNodeRequest;
import com.bipros.project.domain.model.EpsNode;
import com.bipros.project.domain.repository.EpsNodeRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class EpsService {

    private final EpsNodeRepository epsNodeRepository;
    private final ProjectRepository projectRepository;
    private final AuditService auditService;

    public EpsNodeResponse createNode(CreateEpsNodeRequest request) {
        log.info("Creating EPS node with code: {}", request.code());

        if (epsNodeRepository.existsByCode(request.code())) {
            throw new BusinessRuleException("EPS_CODE_DUPLICATE", "EPS node with code '" + request.code() + "' already exists");
        }

        EpsNode node = new EpsNode();
        node.setCode(request.code());
        node.setName(request.name());
        node.setParentId(request.parentId());
        node.setObsId(request.obsId());
        node.setSortOrder(0);

        EpsNode saved = epsNodeRepository.save(node);
        log.info("EPS node created with ID: {}", saved.getId());
        auditService.logCreate("EpsNode", saved.getId(), request);

        return buildNodeResponse(saved, new HashMap<>());
    }

    public EpsNodeResponse updateNode(UUID id, UpdateEpsNodeRequest request) {
        log.info("Updating EPS node: {}", id);

        EpsNode node = epsNodeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("EpsNode", id));

        String oldName = node.getName();
        UUID oldObsId = node.getObsId();
        Integer oldSortOrder = node.getSortOrder();

        node.setName(request.name());
        node.setObsId(request.obsId());
        if (request.sortOrder() != null) {
            node.setSortOrder(request.sortOrder());
        }

        EpsNode updated = epsNodeRepository.save(node);
        log.info("EPS node updated: {}", id);
        auditService.logUpdate("EpsNode", id, "name", oldName, updated.getName());
        auditService.logUpdate("EpsNode", id, "obsId", oldObsId, updated.getObsId());
        auditService.logUpdate("EpsNode", id, "sortOrder", oldSortOrder, updated.getSortOrder());

        return buildNodeResponse(updated, new HashMap<>());
    }

    public EpsNodeResponse moveNode(UUID id, UUID newParentId) {
        log.info("Moving EPS node {} to parent {}", id, newParentId);

        EpsNode node = epsNodeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("EpsNode", id));

        if (newParentId != null) {
            if (newParentId.equals(id)) {
                throw new BusinessRuleException("EPS_CIRCULAR_MOVE", "Cannot move a node under itself");
            }
            // Verify parent exists
            epsNodeRepository.findById(newParentId)
                .orElseThrow(() -> new ResourceNotFoundException("EpsNode (parent)", newParentId));
            // Check for circular reference: walk up from newParentId to root
            UUID current = newParentId;
            while (current != null) {
                if (current.equals(id)) {
                    throw new BusinessRuleException("EPS_CIRCULAR_MOVE", "Cannot move a node under one of its descendants");
                }
                UUID finalCurrent = current;
                EpsNode ancestor = epsNodeRepository.findById(finalCurrent).orElse(null);
                current = ancestor != null ? ancestor.getParentId() : null;
            }
        }

        UUID oldParentId = node.getParentId();
        node.setParentId(newParentId);
        EpsNode updated = epsNodeRepository.save(node);
        log.info("EPS node {} moved from parent {} to {}", id, oldParentId, newParentId);
        auditService.logUpdate("EpsNode", id, "parentId", oldParentId, newParentId);

        return buildNodeResponse(updated, new HashMap<>());
    }

    public void deleteNode(UUID id) {
        log.info("Deleting EPS node: {}", id);

        EpsNode node = epsNodeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("EpsNode", id));
        String label = node.getCode() + " — " + node.getName();

        // Block while the node still has children: deletion only ever removes this single
        // node, so requiring it to be empty keeps any project deeper in the branch safe
        // (you can never delete an ancestor of a node that a project depends on).
        List<EpsNode> children = epsNodeRepository.findByParentIdOrderBySortOrder(id);
        if (!children.isEmpty()) {
            throw new BusinessRuleException("EPS_HAS_CHILDREN",
                "Cannot delete '" + label + "': it has " + count(children.size(), "child node")
                    + ". Delete or move them first.");
        }

        // Block while any project (active or archived) is assigned to this node — every
        // project must belong to an EPS node, so removing it would orphan one.
        long projectCount = projectRepository.findByEpsNodeId(id).size();
        if (projectCount > 0) {
            throw new BusinessRuleException("EPS_HAS_PROJECTS",
                "Cannot delete '" + label + "': " + count(projectCount, "project")
                    + " assigned to it. Reassign them to another EPS node first.");
        }

        epsNodeRepository.delete(node);
        log.info("EPS node deleted: {}", id);
        auditService.logDelete("EpsNode", id);
    }

    /** Formats "{n} {noun}" with naive pluralisation for user-facing block messages. */
    private static String count(long n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    public List<EpsNodeResponse> getTree() {
        log.info("Fetching EPS tree");

        List<EpsNode> allNodes = epsNodeRepository.findAll();
        Map<UUID, EpsNode> nodeMap = allNodes.stream()
            .collect(Collectors.toMap(EpsNode::getId, n -> n));

        List<EpsNode> rootNodes = epsNodeRepository.findByParentIdIsNullOrderBySortOrder();
        return rootNodes.stream()
            .map(node -> buildNodeResponse(node, nodeMap))
            .collect(Collectors.toList());
    }

    public EpsNodeResponse getNode(UUID id) {
        log.info("Fetching EPS node: {}", id);

        EpsNode node = epsNodeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("EpsNode", id));

        List<EpsNode> allNodes = epsNodeRepository.findAll();
        Map<UUID, EpsNode> nodeMap = allNodes.stream()
            .collect(Collectors.toMap(EpsNode::getId, n -> n));

        return buildNodeResponse(node, nodeMap);
    }

    @Transactional(readOnly = true)
    public PagedResponse<NodeSearchResultResponse> search(String q, Pageable pageable) {
        if (q == null || q.isBlank()) {
            return PagedResponse.of(List.of(), 0L, 0,
                pageable.getPageNumber(), pageable.getPageSize());
        }
        int cappedSize = Math.min(Math.max(pageable.getPageSize(), 1), 50);
        Pageable capped = PageRequest.of(pageable.getPageNumber(), cappedSize, pageable.getSort());

        Page<EpsNode> page = epsNodeRepository.searchByCodeOrName(q.trim(), capped);
        List<EpsNode> matches = page.getContent();

        Map<UUID, EpsNode> cache = new HashMap<>();
        for (EpsNode m : matches) cache.put(m.getId(), m);

        Set<UUID> toFetch = matches.stream()
            .map(EpsNode::getParentId)
            .filter(Objects::nonNull)
            .filter(id -> !cache.containsKey(id))
            .collect(Collectors.toCollection(HashSet::new));

        while (!toFetch.isEmpty()) {
            List<EpsNode> ancestors = epsNodeRepository.findAllById(toFetch);
            for (EpsNode a : ancestors) cache.put(a.getId(), a);
            toFetch = ancestors.stream()
                .map(EpsNode::getParentId)
                .filter(Objects::nonNull)
                .filter(id -> !cache.containsKey(id))
                .collect(Collectors.toCollection(HashSet::new));
        }

        List<NodeSearchResultResponse> content = matches.stream()
            .map(n -> toSearchResult(n, cache))
            .collect(Collectors.toList());

        return PagedResponse.of(content, page.getTotalElements(), page.getTotalPages(),
            page.getNumber(), page.getSize());
    }

    private NodeSearchResultResponse toSearchResult(EpsNode node, Map<UUID, EpsNode> cache) {
        List<UUID> ancestorIds = new ArrayList<>();
        List<String> pathParts = new ArrayList<>();
        UUID parent = node.getParentId();
        while (parent != null) {
            EpsNode anc = cache.get(parent);
            if (anc == null) break;
            ancestorIds.add(0, anc.getId());
            pathParts.add(0, anc.getCode());
            parent = anc.getParentId();
        }
        return new NodeSearchResultResponse(
            node.getId(),
            node.getCode(),
            node.getName(),
            node.getParentId(),
            ancestorIds,
            String.join(" > ", pathParts)
        );
    }

    private EpsNodeResponse buildNodeResponse(EpsNode node, Map<UUID, EpsNode> nodeMap) {
        List<EpsNode> children = epsNodeRepository.findByParentIdOrderBySortOrder(node.getId());
        List<EpsNodeResponse> childResponses = children.stream()
            .map(child -> buildNodeResponse(child, nodeMap))
            .collect(Collectors.toList());

        return new EpsNodeResponse(
            node.getId(),
            node.getCode(),
            node.getName(),
            node.getParentId(),
            node.getObsId(),
            node.getSortOrder(),
            childResponses
        );
    }
}
