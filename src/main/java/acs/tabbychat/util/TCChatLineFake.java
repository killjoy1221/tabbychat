package acs.tabbychat.util;

import net.minecraft.client.gui.ChatLine;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;

import com.google.gson.annotations.Expose;

public class TCChatLineFake extends ChatLine {
    protected int updateCounterCreated = -1;
    @Expose
    protected IChatComponent chatComponent;
    protected int chatLineID;

    public TCChatLineFake() {
        super(-1, new ChatComponentText(""), 0);
    }

    public TCChatLineFake(int _counter, IChatComponent _string, int _id) {
        super(_counter, _string, _id);
        this.updateCounterCreated = _counter;
        if (_string == null)
            this.chatComponent = new ChatComponentText("");
        else
            this.chatComponent = _string;
        this.chatLineID = _id;
    }

    @Override
    @Deprecated
    public IChatComponent func_151461_a() {
        return getChatComponent();
    }

    public IChatComponent getChatComponent() {
        return this.chatComponent;
    }

    @Override
    public int getUpdatedCounter() {
        return this.updateCounterCreated;
    }

    @Override
    public int getChatLineID() {
        return this.chatLineID;
    }
}
