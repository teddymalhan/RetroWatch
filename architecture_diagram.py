from diagrams import Cluster, Diagram, Edge
from diagrams.programming.framework import React
from diagrams.programming.language import TypeScript, Java
from diagrams.gcp.ml import VertexAI
from diagrams.gcp.devtools import Scheduler
from diagrams.gcp.analytics import Pubsub
from diagrams.gcp.storage import GCS
from diagrams.onprem.database import PostgreSQL
from diagrams.saas.identity import Auth0

graph_attr = {
    "fontsize": "16",
    "bgcolor": "white",
    "pad": "1.2",
    "splines": "ortho",
    "rankdir": "LR",
    "dpi": "200",
    "size": "22,12.375!",
    "ratio": "fill",
    "nodesep": "0.8",
    "ranksep": "1.8",
    "fontname": "Helvetica",
}

node_attr = {
    "fontsize": "13",
    "fontname": "Helvetica",
}

cluster_attr = {
    "fontsize": "14",
    "fontname": "Helvetica",
    "fontcolor": "#555555",
    "style": "rounded,filled",
    "bgcolor": "#eaf4fb",
    "pencolor": "#b0cfe0",
    "penwidth": "1.5",
    "margin": "24",
}

with Diagram(
    "RetroWatch — System Architecture",
    show=False,
    filename="retrowatch_architecture",
    outformat="png",
    graph_attr=graph_attr,
    node_attr=node_attr,
    direction="LR",
):

    # ── Browser ────────────────────────────────────────────────────────────
    with Cluster("Browser", graph_attr=cluster_attr):
        react   = React("React 19 SPA")
        crt     = TypeScript("CRTModelViewer\nThree.js / R3F")
        zustand = TypeScript("Zustand Stores")
        react - crt
        react - zustand

    # ── Identity ───────────────────────────────────────────────────────────
    clerk = Auth0("Clerk\nJWT / OAuth2")

    # ── Backend ────────────────────────────────────────────────────────────
    with Cluster("Spring Boot  (Java 21)", graph_attr=cluster_attr):
        controllers = Java("Controllers\n/ads  /match  /library\n/video/analyze  /webhooks")
        services    = Java("Services\nAdMatching · Gemini · YouTube\nAdAnalysis · Storage · CloudTasks")
        controllers >> services

    # ── Supabase ───────────────────────────────────────────────────────────
    with Cluster("Supabase", graph_attr={**cluster_attr, "bgcolor": "#eafaf1", "pencolor": "#a8d5b5"}):
        db      = PostgreSQL("PostgreSQL")
        storage = GCS("Object Storage")

    # ── GCP ────────────────────────────────────────────────────────────────
    with Cluster("Google Cloud Platform", graph_attr={**cluster_attr, "bgcolor": "#fef9e7", "pencolor": "#f0d080"}):
        gemini      = VertexAI("Vertex AI\nGemini")
        yt_api      = Pubsub("YouTube\nData API v3")
        cloud_tasks = Scheduler("Cloud Tasks")

    # ── Edges ──────────────────────────────────────────────────────────────

    react   >> clerk
    clerk   >> Edge(style="dashed") >> controllers

    react   >> controllers

    services >> db
    services >> storage

    services >> gemini
    services >> yt_api
    services >> Edge(style="dashed") >> cloud_tasks
    cloud_tasks >> Edge(style="dashed") >> services
