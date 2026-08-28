import { useState } from 'react';
import { App as AntApp, Button, Card, Input, List, Space, Tag, Typography } from 'antd';
import { chat, errorText } from '../api/client';
import type { ChatResponse } from '../types';

const { TextArea } = Input;
const { Paragraph, Text } = Typography;

/** 问答面板：调用 Java 网关 /api/rag/chat（网关再调 Dify chat-messages） */
export default function ChatPanel() {
  const { message } = AntApp.useApp();
  const [query, setQuery] = useState('支付网关调用超时应该怎么办');
  const [appKey, setAppKey] = useState('');
  const [conversationId, setConversationId] = useState('');
  const [loading, setLoading] = useState(false);
  const [resp, setResp] = useState<ChatResponse | null>(null);

  const run = async () => {
    if (!query.trim()) return;
    setLoading(true);
    try {
      setResp(
        await chat({
          query,
          appKey: appKey.trim() || undefined,
          conversationId: conversationId.trim() || undefined,
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
      <Card title="提问（走 Dify 聊天助手 App，回答带引用溯源）">
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <TextArea
            rows={3}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="输入问题，如：支付网关调用超时应该怎么办"
          />
          <Space wrap>
            <Input
              style={{ width: 320 }}
              placeholder="App API Key（不填则用后端配置的 DIFY_APP_API_KEY）"
              value={appKey}
              onChange={(e) => setAppKey(e.target.value)}
            />
            <Input
              style={{ width: 260 }}
              placeholder="conversation_id（续聊，可空）"
              value={conversationId}
              onChange={(e) => setConversationId(e.target.value)}
            />
            <Button type="primary" loading={loading} onClick={run}>
              提问
            </Button>
          </Space>
        </Space>
      </Card>

      {resp && (
        <Card title="回答">
          <Paragraph style={{ whiteSpace: 'pre-wrap' }}>{resp.answer}</Paragraph>
          <Text type="secondary">
            message_id: {resp.message_id} · conversation_id: {resp.conversation_id}
          </Text>
          {resp.records && resp.records.length > 0 && (
            <>
              <Paragraph strong style={{ marginTop: 16 }}>
                引用来源（{resp.records.length}）
              </Paragraph>
              <List
                size="small"
                dataSource={resp.records}
                renderItem={(r, i) => (
                  <List.Item>
                    <Space align="start">
                      <Tag color="blue">#{i + 1}</Tag>
                      <Text type="secondary">
                        {r.segment?.content?.slice(0, 120)}
                        {r.segment?.content && r.segment.content.length > 120 ? '...' : ''}
                      </Text>
                    </Space>
                  </List.Item>
                )}
              />
            </>
          )}
        </Card>
      )}
    </Space>
  );
}
