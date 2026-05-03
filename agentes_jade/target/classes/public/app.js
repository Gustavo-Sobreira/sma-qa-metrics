const state = {
  runs: [],
  selectedRunId: "",
  detail: null,
  timer: null
};

const $ = (id) => document.getElementById(id);

document.addEventListener("DOMContentLoaded", () => {
  bindNavigation();
  bindActions();
  refreshRuns();
  state.timer = setInterval(refreshRuns, 4000);
});

function bindNavigation() {
  document.querySelectorAll(".nav-button").forEach((button) => {
    button.addEventListener("click", () => {
      document.querySelectorAll(".nav-button").forEach((b) => b.classList.remove("active"));
      button.classList.add("active");

      const view = button.dataset.view;
      document.querySelectorAll(".view-panel").forEach((panel) => {
        panel.classList.toggle("hidden", panel.dataset.panel !== view);
      });
    });
  });
}

function bindActions() {
  $("startForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    await startAnalysis();
  });

  $("projectSelect").addEventListener("change", async (event) => {
    state.selectedRunId = event.target.value;
    await loadSelectedRun();
  });

  $("refreshButton").addEventListener("click", refreshRuns);

  $("copyReport").addEventListener("click", async () => {
    const report = $("reportOutput").textContent.trim();
    if (report) {
      await navigator.clipboard.writeText(report);
    }
  });
}

async function startAnalysis() {
  const repository = $("repository").value.trim();
  const version = $("version").value.trim();
  const payload = { repository };
  if (version) payload.version = version;

  try {
    setBanner("");
    const response = await fetch("/webhook", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });

    const data = await response.json();
    if (!response.ok) {
      throw new Error(data.error || `HTTP ${response.status}`);
    }

    state.selectedRunId = data.run_id;
    await refreshRuns();
  } catch (error) {
    setBanner(error.message || "Falha ao iniciar análise");
  }
}

async function refreshRuns() {
  try {
    const response = await fetch("/api/runs");
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    state.runs = await response.json();

    if (!state.selectedRunId && state.runs.length) {
      state.selectedRunId = runIdOf(state.runs[0]);
    }

    renderRunOptions();
    renderProjectList();
    await loadSelectedRun();
  } catch (error) {
    setBanner(`Não foi possível carregar execuções: ${error.message}`);
  }
}

async function loadSelectedRun() {
  if (!state.selectedRunId) {
    renderEmpty();
    return;
  }

  try {
    const response = await fetch(`/api/runs/${encodeURIComponent(state.selectedRunId)}`);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    state.detail = await response.json();
    renderDetail();
  } catch (error) {
    setBanner(`Não foi possível carregar detalhes: ${error.message}`);
  }
}

function renderRunOptions() {
  const select = $("projectSelect");
  select.innerHTML = "";
  state.runs.forEach((run) => {
    const status = run.status || {};
    const option = document.createElement("option");
    option.value = status.run_id || "";
    option.textContent = `${repoName(status.repo_url)} ${status.git_ref ? `(${status.git_ref})` : ""}`;
    if (option.value === state.selectedRunId) option.selected = true;
    select.appendChild(option);
  });
}

function renderProjectList() {
  const list = $("projectList");
  list.innerHTML = "";

  if (!state.runs.length) {
    list.innerHTML = `<div class="project-row"><div><strong>Nenhuma execução</strong><span>Inicie uma análise pelo formulário acima</span></div></div>`;
    return;
  }

  state.runs.forEach((run) => {
    const status = run.status || {};
    const row = document.createElement("button");
    row.type = "button";
    row.className = `project-row ${status.run_id === state.selectedRunId ? "active" : ""}`;
    row.innerHTML = `
      <div>
        <strong>${escapeHtml(repoName(status.repo_url))}</strong>
        <span>${escapeHtml(status.repo_url || "-")}</span>
      </div>
      <div class="status-pill ${status.failed ? "failed" : ""}">${escapeHtml(status.stage || "START")}</div>
    `;
    row.addEventListener("click", async () => {
      state.selectedRunId = status.run_id;
      renderRunOptions();
      renderProjectList();
      await loadSelectedRun();
    });
    list.appendChild(row);
  });
}

function renderDetail() {
  const detail = state.detail || {};
  const status = detail.status || {};
  const codeMetrics = detail.code_metrics || detail.sonar_metrics || {};
  const project = detail.project || {};
  const report = detail.llm_report || {};
  const progress = Number(status.progress_pct || 0);

  $("activeRun").textContent = shortRun(status.run_id);
  $("activeRepo").textContent = `${repoName(status.repo_url)}${status.git_ref ? ` · ${status.git_ref}` : ""}`;
  $("stagePill").textContent = status.stage || "START";
  $("progressPill").textContent = `${progress}%`;
  $("progressValue").textContent = `${progress}%`;
  $("progressBar").style.width = `${Math.max(0, Math.min(100, progress))}%`;

  const bugs = metricValue(codeMetrics, "bugs");
  $("bugsValue").textContent = bugs ?? "-";
  $("commitsValue").textContent = commitCount(project) ?? "-";

  if (status.failed || status.reason) {
    setBanner(status.reason || "Execução marcada como falha");
  } else {
    setBanner("");
  }

  renderMetrics(codeMetrics);
  renderTimeline(status);
  renderDataTable(status, codeMetrics, project);
  renderReport(report);
  renderLogs(detail.logs || []);
}

function renderMetrics(codeMetrics) {
  const values = [
    ["Bugs", metricValue(codeMetrics, "bugs")],
    ["Vulnerabilidades", metricValue(codeMetrics, "vulnerabilities")],
    ["Code smells", metricValue(codeMetrics, "code_smells")],
    ["Complexidade", metricValue(codeMetrics, "complexity")]
  ];

  const numeric = values.map(([, value]) => Number(value || 0));
  const total = numeric.reduce((sum, value) => sum + value, 0);
  $("donutLabel").textContent = total ? String(total) : "-";

  const colors = ["#24c766", "#348761", "#16683b", "#063d1f"];
  if (total > 0) {
    let cursor = 0;
    const segments = numeric.map((value, index) => {
      const start = cursor;
      const end = cursor + (value / total) * 100;
      cursor = end;
      return `${colors[index]} ${start}% ${end}%`;
    });
    $("donutChart").style.background = `conic-gradient(${segments.join(",")})`;
  }

  $("metricLegend").innerHTML = values.map(([label, value], index) => `
    <div class="legend-row">
      <span><i style="background:${colors[index]}"></i>${escapeHtml(label)}</span>
      <b>${escapeHtml(value ?? "NOT AVAILABLE")}</b>
    </div>
  `).join("");
}

function renderTimeline(status) {
  const steps = [
    ["Git", status.git_ok, 20],
    ["Código", status.code_ok, 60],
    ["Projeto", status.project_ok, 75],
    ["LLM", status.llm_ok, 90],
    ["Fim", status.stage === "DONE", 100]
  ];

  $("timeline").innerHTML = steps.map(([label, done, height]) => `
    <div class="timeline-step ${status.failed ? "failed" : done ? "done" : ""}" style="--h:${height * 1.8}px">
      <span>${label}</span>
    </div>
  `).join("");
}

function renderDataTable(status, codeMetrics, project) {
  const rows = [
    ["Run ID", status.run_id],
    ["Repositório", status.repo_url],
    ["Versão", status.git_ref || "default"],
    ["Projeto Sonar", status.sonar_project_key],
    ["Cobertura", metricValue(codeMetrics, "coverage")],
    ["Duplicação", metricValue(codeMetrics, "duplicated_lines_density")],
    ["Linhas NCLOC", metricValue(codeMetrics, "ncloc")],
    ["Commits", commitCount(project)]
  ];

  $("dataTable").innerHTML = rows.map(([key, value]) => `
    <div class="data-row">
      <span>${escapeHtml(key)}</span>
      <b>${escapeHtml(value ?? "NOT AVAILABLE")}</b>
    </div>
  `).join("");
}

function renderReport(report) {
  $("reportOutput").textContent = report && report.report
    ? report.report
    : "Nenhum relatório disponível ainda.";
}

function renderLogs(logs) {
  const stream = $("logStream");
  if (!logs.length) {
    stream.innerHTML = `<div class="log-item"><strong>Sem eventos</strong><code>Aguardando logs dos agentes.</code></div>`;
    return;
  }

  stream.innerHTML = logs.map((log) => {
    const isError = String(log.event || "").includes("FAILED") || String(log.ontology || "").includes("FAILED");
    return `
      <div class="log-item ${isError ? "error" : ""}">
        <strong>${escapeHtml(log.agent || "-")} · ${escapeHtml(log.event || "-")} · ${escapeHtml(log.ontology || "-")}</strong>
        <code>${escapeHtml(log.created_at || "")}\n${escapeHtml(JSON.stringify(log.data || {}, null, 2))}</code>
      </div>
    `;
  }).join("");
}

function renderEmpty() {
  $("activeRun").textContent = "-";
  $("activeRepo").textContent = "Nenhum projeto selecionado";
  $("progressValue").textContent = "0%";
  $("progressBar").style.width = "0%";
  $("projectList").innerHTML = "";
  $("reportOutput").textContent = "Nenhum relatório disponível ainda.";
}

function setBanner(message) {
  const banner = $("errorBanner");
  banner.textContent = message || "";
  banner.classList.toggle("hidden", !message);
}

function runIdOf(run) {
  return run && run.status ? run.status.run_id : "";
}

function metricValue(codeMetrics, key) {
  const measures = codeMetrics?.metrics?.component?.measures || codeMetrics?.metrics?.measures || [];
  const found = measures.find((item) => item.metric === key);
  return found ? found.value : null;
}

function commitCount(project) {
  const commits = project?.git_log_metrics?.commits || project?.metrics?.commits || [];
  return Array.isArray(commits) ? commits.length : null;
}

function repoName(url) {
  if (!url) return "-";
  const clean = String(url).replace(/\.git$/, "");
  return clean.substring(clean.lastIndexOf("/") + 1) || clean;
}

function shortRun(runId) {
  if (!runId) return "-";
  return runId.length > 12 ? `${runId.slice(0, 8)}…` : runId;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}
