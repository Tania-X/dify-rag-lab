import { useState } from 'react';
import {
  App as AntApp,
  Button,
  Card,
  Collapse,
  Input,
  InputNumber,
  List,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd';
import { errorText, listCollections, listObjects, vdbSearch } from '../api/client';
import type { CollectionInfo, SelfHit, WeaviateObject } from '../types';

const { Text } = Typography;

const METHODS = [
  { value: 'near_vector', label: 'near_vector（语义）' },
  { value: 'bm25', label: 'bm25（全文）' },
  { value: 'hybrid', label: 'hybrid（加权融合）' },
];

/** 向量库直查面板：绕过 Dify，直接看 Weaviate 里的 collection 与 chunk */
export default function VdbPanel() {
  const { message } = AntApp.useApp();
  const [collections, setCollections] = useState<CollectionInfo[]>([]);
  const [objectsByClass, setObjectsByClass] = useState<Record<string, WeaviateObject[]>>({});
  const [query, setQuery] = useState('支付网关调用超时应该怎么办');
  const [method, setMethod] = useState('hybrid');
  const [topK, setTopK] = useState(5);
  const [hits, setHits] = useState<SelfHit[]>([]);
  const [searching, setSearching] = useState(false);

  const loadCollections = async () => {
    try {
      setCollections(await listCollections());
    } catch (e) {
      message.error(errorText(e));
    }
  };

  const loadObjects = async (className: string) => {
    try {
      const objects = await listObjects(className, 5);
      setObjectsByClass((prev) => ({ ...prev, [className]: objects }));
    } catch (e) {
      message.error(errorText(e));
    }
  };

  const runSearch = async (className: string) => {
    if (!query.trim()) return;
    setSearching(true);
    try {
      setHits(await vdbSearch(className, { query, method, topK }));
    } catch (e) {
      message.error(errorText(e));
    } finally {
      setSearching(false);
    }
  };

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="large">
      <Card
        title="Weaviate collections（Dify 每个知识库 = 一个 collection）"
        extra={<Button onClick={loadCollections}>刷新</Button>}
      >
        {collections.length === 0 ? (
          <Text type="secondary">尚未加载，点击「刷新」。collection 命名如 Vector_index_&lt;dataset_id&gt;_Node。</Text>
        ) : (
          <Collapse
            items={collections.map((c) => ({
              key: c.class,
              label: (
                <Space>
                  <Tag color="geekblue">{c.class}</Tag>
                  <Text type="secondary">objects: {c.objectCount}</Text>
                </Space>
              ),
              children: (
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Button size="small" onClick={() => loadObjects(c.class)}>
                    查看前 5 个对象（chunk 正文与元数据）
                  </Button>
                  {objectsByClass[c.class]?.map((o, i) => (
                    <Card key={o.id} size="small" title={`#${i + 1} ${o.id.slice(0, 8)}...`}>
                      <Text type="secondary">{JSON.stringify(o.properties)}</Text>
                    </Card>
                  ))}
                  <Space wrap>
                    <Input
                      style={{ width: 360 }}
                      value={query}
                      onChange={(e) => setQuery(e.target.value)}
                      placeholder="查询文本"
                    />
                    <Select style={{ width: 200 }} value={method} onChange={setMethod} options={METHODS} />
                    <span>top_k</span>
                    <InputNumber min={1} max={20} value={topK} onChange={(v) => setTopK(v ?? 5)} />
                    <Button type="primary" loading={searching} onClick={() => runSearch(c.class)}>
                      直查检索
                    </Button>
                  </Space>
                </Space>
              ),
            }))}
          />
        )}
      </Card>

      {hits.length > 0 && (
        <Card title={`直查命中（${method}）`}>
          <List
            dataSource={hits}
            renderItem={(h, i) => (
              <List.Item>
                <Space align="start">
                  <Tag color={i === 0 ? 'green' : 'blue'}>#{i + 1}</Tag>
                  <Space direction="vertical" size={0}>
                    <Tag color="orange">
                      {h.source} score={h.score.toFixed(4)}
                    </Tag>
                    <Text>{h.text}</Text>
                  </Space>
                </Space>
              </List.Item>
            )}
          />
        </Card>
      )}
    </Space>
  );
}
