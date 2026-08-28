package com.example.difyraglab.web;

import com.example.difyraglab.rag.RagService;
import com.example.difyraglab.rag.RagService.SelfHit;
import com.example.difyraglab.weaviate.WeaviateClient;
import com.example.difyraglab.weaviate.WeaviateClient.WeaviateObject;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量库直查接口 —— 亲眼验证 Dify 在 Weaviate 里存了什么、检索怎么算分。
 */
@RestController
@RequestMapping("/api/vdb")
public class VdbController {

    private final WeaviateClient weaviateClient;
    private final RagService ragService;

    public VdbController(WeaviateClient weaviateClient, RagService ragService) {
        this.weaviateClient = weaviateClient;
        this.ragService = ragService;
    }

    /** 列出全部 collection（含每个 collection 的对象数）。 */
    @GetMapping("/collections")
    public List<Map<String, Object>> collections() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : weaviateClient.listCollections()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("class", name);
            m.put("objectCount", weaviateClient.countObjects(name));
            out.add(m);
        }
        return out;
    }

    /** 查看单个 collection 的 schema 与对象数。 */
    @GetMapping("/collections/{name}")
    public Map<String, Object> collection(@PathVariable String name) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("class", name);
        out.put("objectCount", weaviateClient.countObjects(name));
        out.put("schema", weaviateClient.collectionSchema(name));
        return out;
    }

    /** 拉取对象（默认 3 个），看 chunk 正文与元数据。 */
    @GetMapping("/collections/{name}/objects")
    public List<WeaviateObject> objects(@PathVariable String name,
                                        @RequestParam(defaultValue = "3") int limit) {
        return weaviateClient.listObjects(name, limit);
    }

    public record SearchRequest(
            @NotBlank String query,
            String method,        // near_vector | bm25 | hybrid（默认 hybrid）
            @JsonAlias("top_k") Integer topK,
            @JsonAlias("embedding_weight") Double embeddingWeight
    ) {
    }

    /** 直查向量库检索（自实现，不走 Dify）。 */
    @PostMapping("/collections/{name}/search")
    public List<SelfHit> search(@PathVariable String name, @RequestBody SearchRequest req) {
        String method = req.method() == null ? "hybrid" : req.method();
        int topK = req.topK() == null ? 5 : req.topK();
        return ragService.searchCollection(name, req.query(), method, topK, req.embeddingWeight());
    }
}
