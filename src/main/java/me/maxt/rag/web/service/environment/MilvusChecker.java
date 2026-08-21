package me.maxt.rag.web.service.environment;

import me.maxt.rag.web.service.vector.MilvusSession;

/**
 * Milvus 连接性检测：向量库会话 probe() 的适配器（探针语义唯一所有者为 MilvusSession）。
 */
public class MilvusChecker implements DependencyChecker {

    private final MilvusSession session;

    public MilvusChecker(MilvusSession session) {
        this.session = session;
    }

    @Override
    public String name() { return "milvus"; }

    @Override
    public CheckResult check() {
        MilvusSession.ProbeResult p = session.probe();
        if (p.reachable()) {
            return new CheckResult(name(), CheckResult.Category.SERVICE,
                    CheckResult.Status.OK, p.version(), p.message());
        }
        return new CheckResult(name(), CheckResult.Category.SERVICE,
                CheckResult.Status.MISSING, null, p.message());
    }
}
