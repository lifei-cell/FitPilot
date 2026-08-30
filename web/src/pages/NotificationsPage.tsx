import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Bell, CheckCheck, Trophy } from "lucide-react";
import { api } from "../api/client";
import { Empty, PageHeader, Panel } from "../components/PageParts";
type Notification = {
  id: number;
  type: string;
  title: string;
  message: string;
  read: boolean;
  createdAt: string;
};
export function NotificationsPage() {
  const client = useQueryClient();
  const items = useQuery({
    queryKey: ["notifications"],
    queryFn: () => api<Notification[]>("/notifications"),
  });
  const readAll = useMutation({
    mutationFn: () => api("/notifications/read-all", { method: "POST" }),
    onSuccess: () => client.invalidateQueries({ queryKey: ["notifications"] }),
  });
  const read = useMutation({
    mutationFn: (id: number) =>
      api(`/notifications/${id}/read`, { method: "POST" }),
    onSuccess: () => client.invalidateQueries({ queryKey: ["notifications"] }),
  });
  return (
    <main className="page">
      <PageHeader
        eyebrow="STAY INFORMED"
        title="通知"
        description="个人纪录与训练关键事件集中在这里。"
        action={
          <button className="secondary-button" onClick={() => readAll.mutate()}>
            <CheckCheck size={17} />
            全部已读
          </button>
        }
      />
      <Panel>
        {items.data?.length ? (
          <div className="notification-list">
            {items.data.map((item) => (
              <button
                key={item.id}
                className={item.read ? "read" : ""}
                onClick={() => !item.read && read.mutate(item.id)}
              >
                <span className="notification-icon">
                  {item.type === "PERSONAL_RECORD" ? <Trophy /> : <Bell />}
                </span>
                <div>
                  <strong>{item.title}</strong>
                  <p>{item.message}</p>
                  <small>
                    {new Date(item.createdAt).toLocaleString("zh-CN")}
                  </small>
                </div>
                {!item.read && <i />}
              </button>
            ))}
          </div>
        ) : (
          <Empty
            title="暂无通知"
            text="刷新个人纪录时，我们会第一时间告诉你。"
          />
        )}
      </Panel>
    </main>
  );
}
