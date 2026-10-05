import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App";
import "./theme.css";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    {/* Whatever path this build is served under - /dashboard/ inside the jar,
        /<repo>/ on GitHub Pages. Vite fills it in at build time. */}
    <BrowserRouter basename={import.meta.env.BASE_URL}>
      <App />
    </BrowserRouter>
  </StrictMode>
);
