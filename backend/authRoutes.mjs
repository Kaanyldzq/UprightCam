import { Router } from "express";
import { login, logout, userRole } from "./authController.mjs";
import { authRequired } from "./auth.mjs";

const router = Router();

router.post("/login", login);
router.post("/logout", authRequired(), logout);
router.post("/user/role", authRequired(), userRole); // Android burayı çağırıyor

export default router;
