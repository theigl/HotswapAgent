package org.hotswap.agent.plugin.owb.command;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import org.hotswap.agent.annotation.FileEvent;
import org.hotswap.agent.command.Command;
import org.hotswap.agent.command.MergeableCommand;
import org.hotswap.agent.logging.AgentLogger;
import org.hotswap.agent.plugin.owb.BeanReloadStrategy;
import org.hotswap.agent.watch.WatchFileEvent;

/**
 * BeanClassRefreshCommand. Collect all classes definitions/redefinitions for application
 *
 * 1. Merge all commands (definition, redefinition) for single archive to single command.
 * 2. Call proxy redefinitions in BeanDeploymentArchiveAgent for all merged commands
 * 3. Call bean class reload in BeanDepoymentArchiveAgent for all merged commands
 *
 * @author Vladimir Dvorak
 */
public class BeanClassRefreshCommand extends MergeableCommand {

    private static AgentLogger LOGGER = AgentLogger.getLogger(BeanClassRefreshCommand.class);

    ClassLoader classLoader;

    String className;

    String classSignForProxyCheck;

    String classSignByStrategy;

    String strBeanReloadStrategy;

    // either event or classDefinition is set by constructor (watcher or transformer)
    WatchFileEvent event;

    public BeanClassRefreshCommand(ClassLoader classLoader, String className, String classSignForProxyCheck,
            String classSignByStrategy, BeanReloadStrategy beanReloadStrategy) {
        this.classLoader = classLoader;
        this.className = className;
        this.classSignForProxyCheck = classSignForProxyCheck;
        this.classSignByStrategy = classSignByStrategy;
        this.strBeanReloadStrategy = beanReloadStrategy != null ? beanReloadStrategy.toString() : null;
    }

    public BeanClassRefreshCommand(ClassLoader classLoader, String archivePath, WatchFileEvent event) {

        this.classLoader = classLoader;
        this.event = event;

        // strip from URI prefix up to basePackage and .class suffix.
        String classFullPath = event.getURI().getPath();
        int index = classFullPath.indexOf(archivePath);
        if (index == 0) {
            String classPath = classFullPath.substring(archivePath.length());
            classPath = classPath.substring(0, classPath.indexOf(".class"));
            if (classPath.startsWith("/")) {
                classPath = classPath.substring(1);
            }
            this.className = classPath.replace("/", ".");
        } else {
            LOGGER.error("Archive path '{}' doesn't match with classFullPath '{}'", archivePath, classFullPath);
        }
    }

    @Override
    public void executeCommand() {
        List<Command> mergedCommands = popMergedCommands();
        mergedCommands.add(0, this);

        do {
            // First step : recreate all proxies
//            for (Command command: mergedCommands) {
//                ((BeanClassRefreshCommand)command).recreateProxy(mergedCommands);
//            }

            // Second step : reload beans
            for (Command command: mergedCommands) {
                ((BeanClassRefreshCommand)command).reloadBean(mergedCommands);
            }
            mergedCommands = popMergedCommands();
        } while (!mergedCommands.isEmpty());
    }

    // TODO:
    private void recreateProxy(List<Command> mergedCommands) {
    }


    public void reloadBean(List<Command> mergedCommands) {
        if (isDeleteEvent(mergedCommands)) {
            LOGGER.trace("Skip OWB reload for delete event on class '{}'", className);
            return;
        }
        if (className != null) {
            try {
                LOGGER.debug("Executing BeanClassRefreshAgent.reloadBean('{}')", className);
                Class<?> agentClazz = Class.forName(BeanClassRefreshAgent.class.getName(), true, classLoader);
                Method bdaMethod  = agentClazz.getDeclaredMethod("reloadBean",
                        new Class[] { ClassLoader.class,
                                      String.class,
                                      String.class,
                                      String.class
                        }
                );
                bdaMethod.invoke(null,
                        classLoader,
                        className,
                        classSignByStrategy,
                        strBeanReloadStrategy // passed as String since BeanArchiveAgent has different classloader
                );
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("Plugin error, method not found", e);
            } catch (InvocationTargetException e) {
                LOGGER.error("Error reloadBean class {} in classLoader {}", e, className, classLoader);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Plugin error, illegal access", e);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Plugin error, CDI class not found in classloader", e);
            }
        }
    }

    /**
     * Check all merged events with same className for delete and create events. If delete without create is found, than assume
     * file was deleted.
     * @param mergedCommands
     */
    private boolean isDeleteEvent(List<Command> mergedCommands) {
        boolean createFound = false;
        boolean deleteFound = false;
        for (Command cmd : mergedCommands) {
            BeanClassRefreshCommand refreshCommand = (BeanClassRefreshCommand) cmd;
            if (className.equals(refreshCommand.className)) {
                if (refreshCommand.event != null) {
                    if (refreshCommand.event.getEventType().equals(FileEvent.DELETE))
                        deleteFound = true;
                    if (refreshCommand.event.getEventType().equals(FileEvent.CREATE))
                        createFound = true;
                }
            }
        }

        LOGGER.trace("isDeleteEvent result {}: createFound={}, deleteFound={}", createFound, deleteFound);
        return !createFound && deleteFound;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        BeanClassRefreshCommand that = (BeanClassRefreshCommand) o;

        if (!classLoader.equals(that.classLoader)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = classLoader.hashCode();
        result = 31 * result + (className != null ? className.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "BeanClassRefreshCommand{" +
                "classLoader=" + classLoader +
                ", className='" + className + '\'' +
                '}';
    }
}
