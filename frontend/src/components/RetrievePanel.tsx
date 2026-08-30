import { useEffect, useState } from 'react';
import { App as AntApp, Button, Card, Input, InputNumber, List, Select, Space, Tag, Typography } from 'antd';
import { errorText, listMetadataFields, retrieve } from '../api/client';
import type { MetadataField, RetrieveHit } from '../types';

const { TextArea } = Input;
const { Text } = Typography;

const METHODS = [
  { value: 'semantic_search', label: 'semantic_search（向量）' },
  { value: 'full_text_search', label: 'full_text_search（BM25）' },
  { value: 'hybrid_search', label: 'hybrid_search（混合）' },
];

/** 检索对比面板：同 query 跑三种方式，观察 score 口径差异 */
export default function RetrievePanel() {
  const { message } = AntApp.useApp();
  const [query, setQuery] = useState('支付网关调用超时应该怎么办');
  const [method, setMethod] = useState('hybrid_search');
  const [topK, setTopK] = useState(5);
  const [scoreThreshold, setScoreThreshold] = useState<number | null>(null);
  const [metadataFields, setMetadataFields] = useState<MetadataField[]>([]);
  const [metadataValues, setMetadataValues] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);
  const [hits, setHits] = useState<RetrieveHit[]>([]);

  useEffect(() => {
    listMetadataFields()
      .then(setMetadataFields)
      .catch(() => {
        // 元数据字段拉取失败不阻塞检索，仅不显示筛选条件
      });
  }, []);

  const run = async () => {
    if (!query.trim()) return;
    const metadataFilters = metadataFields
      .filter((f) => metadataValues[f.name]?.trim())
      .map((f) => ({
        name: f.name,
        operator: 'is',
        value: metadataValues[f.name].trim(),
      }));
    setLoading(true);
    try {
      setHits(
        await retrieve({
          query,
          searchMethod: method,
          topK,
          scoreThreshold,
          metadataFilters,
        }),
      );
    } catch (e) {
      message.error(errorText(e));
    } finally {
      setLoading(false);
    }
  };

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="large">
      <Card title="检索（Dify 知识库 API）">
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <TextArea
            rows={2}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="输入查询文本"
          />
          {metadataFields.length > 0 && (
            <Space wrap>
              {metadataFields.map((f) => (
                <Input
                  key={f.id}
                  style={{ width: 180 }}
                  addonBefore={f.name}
                  placeholder={`${f.name} 过滤值，如 2025`}
                  value={metadataValues[f.name] || ''}
                  onChange={(e) =>
                    setMetadataValues((prev) => ({ ...prev, [f.name]: e.target.value }))
                  }
                />
              ))}
            </Space>
          )}
          <Space wrap>
            <Select
              style={{ width: 260 }}
              value={method}
              onChange={setMethod}
              options={METHODS}
            />
            <span>top_k</span>
            <InputNumber min={1} max={20} value={topK} onChange={(v) => setTopK(v ?? 5)} />
            <span>score_threshold</span>
            <InputNumber
              min={-1}
              max={1}
              step={0.1}
              placeholder="不过滤"
              value={scoreThreshold}
              onChange={(v) => setScoreThreshold(v ?? null)}
            />
            <Button type="primary" loading={loading} onClick={run}>
              检索
            </Button>
          </Space>
        </Space>
      </Card>

      {hits.length > 0 && (
        <Card title={`命中 ${hits.length} 条（${method}）`}>
          <List
            dataSource={hits}
            renderItem={(h, i) => (
              <List.Item>
                <Space align="start">
                  <Tag color={i === 0 ? 'green' : 'blue'}>#{i + 1}</Tag>
                  <Space direction="vertical" size={0}>
                    <Tag color="orange">score={h.score.toFixed(3)}</Tag>
                    <Text>{h.content}</Text>
                    <Text type="secondary">
                      文档: {h.documentName || h.documentId} · segment: {h.segmentId}
                    </Text>
                  </Space>
                </Space>
              </List.Item>
            )}
          />
          <Text type="secondary">
            提示：semantic/hybrid 的 score ≈ 1 − 余弦距离（[-1,1]）；full_text 为 BM25 原始分，量纲不同，不可跨方法比较。
          </Text>
        </Card>
      )}
    </Space>
  );
}
