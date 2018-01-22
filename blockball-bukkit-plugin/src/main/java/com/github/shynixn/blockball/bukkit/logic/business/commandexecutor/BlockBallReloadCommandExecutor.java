package com.github.shynixn.blockball.bukkit.logic.business.commandexecutor;

import com.github.shynixn.blockball.api.business.controller.GameController;
import com.github.shynixn.blockball.bukkit.logic.business.configuration.Config;
import com.github.shynixn.blockball.bukkit.logic.business.controller.GameRepository;
import com.google.inject.Inject;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Created by Shynixn 2017.
 * <p>
 * Version 1.1
 * <p>
 * MIT License
 * <p>
 * Copyright (c) 2017 by Shynixn
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
public class BlockBallReloadCommandExecutor extends SimpleCommandExecutor.Registered {
    private final GameController gameController;

    /**
     * Initializes a new commandExecutor by command, plugin.
     *
     * @param plugin  plugin
     */
    @Inject
    public BlockBallReloadCommandExecutor(GameRepository gameController, Plugin plugin) {
        super("blockballreload", (JavaPlugin) plugin);
        this.gameController = gameController;
    }

    /**
     * Can be overwritten to listener to all executed commands.
     *
     * @param sender sender
     * @param args   args
     */
    @Override
    public void onCommandSenderExecuteCommand(CommandSender sender, String[] args) {
        Config.getInstance().reload();
        this.gameController.reload();
        sender.sendMessage(Config.getInstance().getPrefix() + ChatColor.GREEN + "Reloaded BlockBall.");
    }
}
