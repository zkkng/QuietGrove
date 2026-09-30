package gmevents;
import com.sun.tools.attach.VirtualMachine;
/** Attach an explicitly supplied operator probe or control agent to the user-owned JVM. */
public final class AttachProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=3) throw new IllegalArgumentException("pid agent.jar agent-options");
        VirtualMachine vm=VirtualMachine.attach(args[0]);try {vm.loadAgent(args[1],args[2]);} finally {vm.detach();}
    }
}
