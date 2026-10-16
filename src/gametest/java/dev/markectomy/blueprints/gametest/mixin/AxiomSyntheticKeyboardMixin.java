package dev.markectomy.blueprints.gametest.mixin;

import com.moulberry.axiom.utils.InputHelper;
import net.fabricmc.fabric.impl.client.gametest.TestInputImpl;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Axiom polls GLFW directly, bypassing Fabric's synthetic key state. Test mod only. */
@Mixin(value = InputHelper.class, remap = false)
abstract class AxiomSyntheticKeyboardMixin {
    @Redirect(
        method = {"isKeyDownRaw", "isCtrlDownRaw", "isShiftDownRaw", "isAltDownRaw", "isSuperDownRaw"},
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwGetKey(JI)I"),
        require = 9
    )
    private static int blueprints$pollSyntheticKeyboard(long window, int key) {
        return TestInputImpl.isKeyDown(key) ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE;
    }
}
