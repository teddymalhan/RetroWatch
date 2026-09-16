from diagrams import Cluster, Diagram, Edge
from diagrams.programming.framework import React
from diagrams.programming.language import TypeScript, Java
from diagrams.onprem.database import PostgreSQL
from diagrams.onprem.network import Caddy
from diagrams.onprem.compute import Server
from diagrams.saas.identity import Auth0
from diagrams.generic.storage import Storage

graph_attr = {
    "fontsize": "26",
    "bgcolor": "white",
    "pad": "1.4",
    "splines": "ortho",
    "rankdir": "LR",
    "dpi": "160",
    "size": "24,13.5!",
    "ratio": "fill",
    "nodesep": "1.0",
    "ranksep": "2.0",
    "fontname": "Helvetica",
}

node_attr = {
    "fontsize": "18",
    "fontname": "Helvetica",
}

cluster_attr = {
    "fontsize": "20",
    "fontname": "Helvetica",
    "fontcolor": "#444444",
    "style": "rounded,filled",
    "bgcolor": "#eaf4fb",
    "pencolor": "#b0cfe0",
    "penwidth": "1.5",
    "margin": "28",
}

# Self-hosted: every box inside this cluster runs from docker-compose.yml on your
# own VPS. Nothing in here is a managed cloud service.
vps_attr = {**cluster_attr, "bgcolor": "#eef7ee", "pencolor": "#a8d5b5"}

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

    # ── Self-hosted VPS ────────────────────────────────────────────────────
    with Cluster("Your VPS  (docker compose)", graph_attr=vps_attr):
        caddy = Caddy("Caddy\nTLS reverse proxy")

        with Cluster("Spring Boot 4  (Java 21)", graph_attr=cluster_attr):
            controllers = Java("Controllers\n/ads  /match  /library\n/video/analyze  /webhooks")
            services    = Java("Services\nAdMatching · Gemini · YouTube\nAdAnalysis · Storage")
            queue       = Java("JobDispatcher\n+ JobQueueService")
            controllers >> services
            services >> queue

        db    = PostgreSQL("PostgreSQL\njobs · uploads · analysis")
        store = Storage("MinIO\nS3 object storage")

        caddy >> controllers
        services - db
        services - store
        queue >> Edge(color="#999999", style="dashed") >> db

    # ── External APIs ──────────────────────────────────────────────────────
    with Cluster("External APIs  (optional)", graph_attr={**cluster_attr, "bgcolor": "#fef9e7", "pencolor": "#f0d080"}):
        gemini = Server("Gemini\nREST or self-hosted\ngateway")
        yt_api = Server("YouTube\nData API v3")

    # ── Edges ──────────────────────────────────────────────────────────────

    react   >> caddy
    react   >> clerk
    clerk   >> Edge(style="dashed") >> controllers

    services >> gemini
    services >> yt_api
