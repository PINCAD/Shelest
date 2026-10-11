import React from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import DownloadPage from "./DownloadPage";
import "./styles.css";
import "./workspace.css";
import "./design.css";

const isDownloadPage = /^\/download\/?$/.test(window.location.pathname);

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    {isDownloadPage ? <DownloadPage /> : <App />}
  </React.StrictMode>,
);
