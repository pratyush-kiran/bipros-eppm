package com.bipros.project.application.service;

import com.bipros.common.dto.PagedResponse;
import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.util.AuditService;
import com.bipros.project.application.dto.CreateEpsNodeRequest;
import com.bipros.project.application.dto.EpsNodeResponse;
import com.bipros.project.application.dto.NodeSearchResultResponse;
import com.bipros.project.application.dto.UpdateEpsNodeRequest;
import com.bipros.project.domain.model.ObsNode;
import com.bipros.project.domain.repository.ObsNodeRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.project.domain.repository.WbsNodeRepository;
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
public class ObsService {

    private final ObsNodeRepository obsNodeRepository;
    private final ProjectRepository projectRepository;
    private final WbsNodeRepository wbsNodeRepository;
    private final AuditService auditService;

    public EpsNodeResponse createNode(CreateEpsNodeRequest request) {
        log.info("Creating OBS node with code: {}", request.code());

        if (obsNodeRepository.existsByCode(request.code())) {
            throw new BusinessRuleException("OBS_CODE_DUPLICATE", "OBS node with code '" + request.code() + "' already exists");
        }

        ObsNode node = new ObsNode();
        node.setCode(request.code());
        node.setName(request.name());
        node.setParentId(request.parentId());
        node.setSortOrder(0);

        ObsNode saved = obsNodeRepository.save(node);
        log.info("OBS node created with ID: {}", saved.getId());
        auditService.logCreate("ObsNode", saved.getId(), request);

        return buildNodeResponse(saved, new HashMap<>());
    }

    public EpsNodeResponse updateNode(UUID id, UpdateEpsNodeRequest request) {
        log.info("Updating OBS node: {}", id);

        ObsNode node = obsNodeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("ObsNode", id));

        String oldName = node.getName();
        Integer oldSortOrder = node.getSortOrder();

        node.setName(request.name());
        if (request.sortOrder() != null) {
            node.setSortOrder(request.sortOrder());
        }

        ObsNode updated = obsNodeRepository.save(node);
        log.info("OBS node updated: {}", id);
        auditService.logUpdate("ObsNode", id, "name", oldName, updated.getName());
        auditService.logUpdate("ObsNode", id, "sortOrder", oldSortOrder, updated.getSortOrder());

        return buildNodeResponse(updated, new HashMap<>());
    }

    public void deleteNode(UUID id) {
        log.info("Deleting OBS node: {}", id);

        ObsNode node = obsNodeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("ObsNode", id));
        String label = node.getCode() + " — " + node.getName();

        // Block while the node still has children (deletion removes this single node only).
        List<ObsNode> children = obsNodeRepository.findByParentIdOrderBySortOrder(id);
        if (!children.isEmpty()) {
            throw new BusinessRuleException("OBS_HAS_CHILDREN",
                "Cannot delete '" + label + "': it has " + count(children.size(), "child node")
                    + ". Delete or move them first.");
        }

        // Block while anything still references this OBS node. Both projects and WBS
        // elements carry an obs_node_id for responsibility assignment; deleting the node
        // would orphan those references.
        long projectCount = projectRepository.countByObsNodeId(id);
        long wbsCount = wbsNodeRepository.countByObsNodeId(id);
        if (projectCount > 0 || wbsCount > 0) {
            List<String> parts = new ArrayList<>();
            if (projectCount > 0) parts.add(count(projectCount, "project"));
            if (wbsCount > 0) parts.add(count(wbsCount, "WBS element"));
            throw new BusinessRuleException("OBS_IN_USE",
                "Cannot delete '" + label + "': it's assigned to " + String.join(" and ", parts)
                    + ". Reassign them first.");
        }

        obsNodeRepository.delete(node);
        log.info("OBS node deleted: {}", id);
        auditService.logDelete("ObsNode", id);
    }

    /** Formats "{n} {noun}" with naive pluralisation for user-facing block messages. */
    private static String count(long n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    public List<EpsNodeResponse> getTree() {
        log.info("Fetching OBS tree");

        List<ObsNode> allNodes = obsNodeRepository.findAll();
        Map<UUID, ObsNode> nodeMap = allNodes.stream()
            .collect(Collectors.toMap(ObsNode::getId, n -> n));

        List<ObsNode> rootNodes = obsNodeRepository.findByParentIdIsNullOrderBySortOrder();
        return rootNodes.stream()
            .map(node -> buildNodeResponse(node, nodeMap))
            .collect(Collectors.toList());
    }

    public EpsNodeResponse getNode(UUID id) {
        log.info("Fetching OBS node: {}", id);

        ObsNode node = obsNodeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("ObsNode", id));

        List<ObsNode> allNodes = obsNodeRepository.findAll();
        Map<UUID, ObsNode> nodeMap = allNodes.stream()
            .collect(Collectors.toMap(ObsNode::getId, n -> n));

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

        Page<ObsNode> page = obsNodeRepository.searchByCodeOrName(q.trim(), capped);
        List<ObsNode> matches = page.getContent();

        Map<UUID, ObsNode> cache = new HashMap<>();
        for (ObsNode m : matches) cache.put(m.getId(), m);

        Set<UUID> toFetch = matches.stream()
            .map(ObsNode::getParentId)
            .filter(Objects::nonNull)
            .filter(id -> !cache.containsKey(id))
            .collect(Collectors.toCollection(HashSet::new));

        while (!toFetch.isEmpty()) {
            List<ObsNode> ancestors = obsNodeRepository.findAllById(toFetch);
            for (ObsNode a : ancestors) cache.put(a.getId(), a);
            toFetch = ancestors.stream()
                .map(ObsNode::getParentId)
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

    private NodeSearchResultResponse toSearchResult(ObsNode node, Map<UUID, ObsNode> cache) {
        List<UUID> ancestorIds = new ArrayList<>();
        List<String> pathParts = new ArrayList<>();
        UUID parent = node.getParentId();
        while (parent != null) {
            ObsNode anc = cache.get(parent);
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

    private EpsNodeResponse buildNodeResponse(ObsNode node, Map<UUID, ObsNode> nodeMap) {
        List<ObsNode> children = obsNodeRepository.findByParentIdOrderBySortOrder(node.getId());
        List<EpsNodeResponse> childResponses = children.stream()
            .map(child -> buildNodeResponse(child, nodeMap))
            .collect(Collectors.toList());

        return new EpsNodeResponse(
            node.getId(),
            node.getCode(),
            node.getName(),
            node.getParentId(),
            null,
            node.getSortOrder(),
            childResponses
        );
    }
}
