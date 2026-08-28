import { Layout, Tabs } from 'antd';
import ChatPanel from './components/ChatPanel';
import RetrievePanel from './components/RetrievePanel';
import VdbPanel from './components/VdbPanel';

const { Header, Content } = Layout;

export default function App() {
  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header style={{ color: '#fff', fontSize: 18, fontWeight: 600 }}>
        Dify RAG Lab · 研发知识库助手
      </Header>
      <Content style={{ padding: 24 }}>
        <Tabs
          items={[
            { key: 'chat', label: '问答', children: <ChatPanel /> },
            { key: 'retrieve', label: '检索对比', children: <RetrievePanel /> },
            { key: 'vdb', label: '向量库直查', children: <VdbPanel /> },
          ]}
        />
      </Content>
    </Layout>
  );
}
