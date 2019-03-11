package dev.alangomes.springspigot;

import dev.alangomes.springspigot.configuration.DynamicValue;
import dev.alangomes.springspigot.configuration.Instance;
import dev.alangomes.springspigot.context.Context;
import dev.alangomes.springspigot.picocli.CommandLineDefinition;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

import java.util.List;
import java.util.concurrent.Callable;

import static org.apache.commons.lang.BooleanUtils.toBoolean;

@Component
@ConditionalOnClass(Bukkit.class)
class CommandInterceptor implements Listener {

    @Setter(AccessLevel.PACKAGE)
    @DynamicValue("${spigot.messages.command_error}")
    private Instance<String> commandErrorMessage;

    @Setter(AccessLevel.PACKAGE)
    @DynamicValue("${spigot.messages.missing_parameter_error}")
    private Instance<String> missingParameterErrorMessage;

    @Setter(AccessLevel.PACKAGE)
    @DynamicValue("${spigot.messages.parameter_error}")
    private Instance<String> parameterErrorMessage;

    @Setter(AccessLevel.PACKAGE)
    @Getter(AccessLevel.PACKAGE)
    @DynamicValue("${spigot.commands.enable_cache:true}")
    private Instance<Boolean> cacheEnabled;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private CommandLineDefinition cli;

    @Autowired
    private Context context;

    private CommandLine commandLineCache;

    private final Logger logger = LoggerFactory.getLogger(CommandInterceptor.class);

    @EventHandler
    void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (event.isCancelled()) return;
        Player player = event.getPlayer();
        context.runWithSender(player, () -> {
            boolean executed = runCommand(player, event.getMessage().substring(1));
            event.setCancelled(executed);
        });
    }

    @EventHandler
    void onServerCommand(ServerCommandEvent event) {
        if (event.isCancelled()) return;
        CommandSender sender = event.getSender();
        context.runWithSender(sender, () -> {
            boolean executed = runCommand(sender, event.getCommand());
            event.setCancelled(executed);
        });
    }

    private boolean runCommand(CommandSender sender, String commandText) {
        try {
            if (!toBoolean(cacheEnabled.get()) || commandLineCache == null) {
                commandLineCache = cli.build(applicationContext);
            }
            List<CommandLine> commands = commandLineCache.parse(commandText.split(" "));
            for (CommandLine commandLine : commands) {
                Object command = commandLine.getCommand();

                if (command instanceof Runnable) {
                    ((Runnable) command).run();
                } else if (command instanceof Callable) {
                    Object result = ((Callable) command).call();
                    outputResult(sender, result);
                }
            }
            return !commands.isEmpty();
        } catch (CommandLine.UnmatchedArgumentException ignored) {
        } catch (CommandLine.MissingParameterException ex) {
            String message = String.format(missingParameterErrorMessage.get(), ex.getMissing().get(0).paramLabel());
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            return true;
        } catch (CommandLine.ParameterException ex) {
            String message = String.format(parameterErrorMessage.get(), ex.getArgSpec().paramLabel());
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            return true;
        } catch (CommandException ex) {
            sender.sendMessage(ChatColor.RED + ex.getMessage());
            return true;
        } catch (Exception ex) {
            logger.error("Unexpected exception while running /" + commandText, ex);
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', commandErrorMessage.get()));
            return true;
        }
        return false;
    }

    private void outputResult(CommandSender sender, Object result) {
        if (result instanceof String) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', (String) result));
        } else if (result instanceof Iterable) {
            ((Iterable<?>) result).forEach(res -> outputResult(sender, res));
        }
    }

}
