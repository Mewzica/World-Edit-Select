package dev.wes.mixin;

import dev.wes.chat.ChatTrigger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 混入 {@link TextFieldWidget}，监听「文本变化」。
 *
 * <p>这是整个模组最关键的一个注入点，原因是 1.20.1 的文本框有三条写入路径：</p>
 * <ul>
 *   <li>{@code write(String)} —— 打字、粘贴。它<b>直接写字段</b>，
 *       不会调用 {@code setText}，所以挂 setText 是抓不到打字的；</li>
 *   <li>{@code setText(String)} —— 程序化赋值、输入法上屏、历史回填；</li>
 *   <li>{@code erase*} —— 删字（我们不需要关心）。</li>
 * </ul>
 * <p>但前两条最后都会调用私有方法 {@code onChanged(String)}，
 * 所以挂它一个点就能覆盖全部输入方式。</p>
 *
 * <p>安全性：全客户端只有一个 {@link ChatScreen}，它内部也只有一个文本框，
 * 因此「当前界面是 ChatScreen」就足以认定 {@code this} 就是聊天输入框，
 * 不需要额外的 Accessor，也就少了一处可能被其他模组冲突的地方。</p>
 */
@Mixin(TextFieldWidget.class)
public abstract class TextFieldWidgetMixin {

    @Inject(method = "onChanged", at = @At("TAIL"))
    private void wes$onTextChanged(String text, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen instanceof ChatScreen screen) {
            ChatTrigger.check(screen, text);
        }
    }
}
