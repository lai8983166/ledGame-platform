import { createApp } from "vue";
import App from "./App.vue";
import OperatorApp from "./OperatorApp.vue";
import "./style.css";

const windowKind = window.registrationDesktop?.windowKind ?? new URLSearchParams(window.location.search).get("window");
if (windowKind === "operator") {
  void import("./operator.css");
  createApp(OperatorApp).mount("#app");
} else {
  createApp(App).mount("#app");
}
