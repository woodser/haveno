package bisq.core.btc.setup;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import monero.common.MoneroError;
import monero.wallet.MoneroWalletRpc;

/**
 * Manages monero-wallet-rpc processes bound to ports.
 */
public class MoneroWalletRpcManager {
  
  private static int NUM_ALLOWED_ATTEMPTS = 1; // allow this many attempts to bind to an assigned port
  private Integer startPort;
  private Map<Integer, RegisteredPort> registeredPorts = new HashMap<Integer, RegisteredPort>();

  /**
   * Manage monero-wallet-rpc instances by auto-assigning ports.
   */
  public MoneroWalletRpcManager() { }

  /**
   * Manage monero-wallet-rpc instances by assigning consecutive ports from a starting port.
   * 
   * @param startPort is the starting port to bind to
   */
  public MoneroWalletRpcManager(int startPort) {
    this.startPort = startPort;
  }
  
  /**
   * Start a new instance of monero-wallet-rpc.
   *  
   * @param cmd command line parameters to start monero-wallet-rpc
   * @return a client connected to the monero-wallet-rpc instance
   */
  public MoneroWalletRpc startInstance(List<String> cmd) {
    
    // preserve original cmd
    cmd = new ArrayList<String>(cmd);
    
    try {
      
      // register given port
      if (cmd.indexOf("--rpc-bind-port") >= 0) {
        int port = Integer.valueOf(cmd.indexOf("--rpc-bind-port") + 1);
        RegisteredPort registeredPort = new RegisteredPort();
        System.out.println(cmd);
        registeredPort.walletRpc = new MoneroWalletRpc(cmd); // starts monero-wallet-rpc process
        registeredPorts.put(port, registeredPort);
        return registeredPort.walletRpc;
      }
      
      // register assigned ports up to maximum attempts
      else {
        int numAttempts = 0;
        while (numAttempts < NUM_ALLOWED_ATTEMPTS) {
          int port = registerPort();
          try {
            RegisteredPort registeredPort = registeredPorts.get(port);
            cmd.add("--rpc-bind-port");
            cmd.add("" + port);
            System.out.println(cmd);
            registeredPort.walletRpc = new MoneroWalletRpc(cmd); // start monero-wallet-rpc process
            return registeredPort.walletRpc;
          } catch (Exception e) {
            numAttempts++;
            if (numAttempts >= NUM_ALLOWED_ATTEMPTS) throw e;
          }
        }
        throw new MoneroError("Unable to start monero-wallet-rpc instance after " + NUM_ALLOWED_ATTEMPTS + " attempts");
      }
    } catch (IOException e) {
      throw new MoneroError(e);
    }
  }
  
  /**
   * Stop an instance of monero-wallet-rpc.
   * 
   * @param walletRpc the client connected to the monero-wallet-rpc instance to stop
   */
  public void stopInstance(MoneroWalletRpc walletRpc) {
    boolean found = false;
    for (Map.Entry<Integer, RegisteredPort> entry : registeredPorts.entrySet()) {
      if (walletRpc == entry.getValue().walletRpc) {
        walletRpc.stop();
        found = true;
        try { unregisterPort(entry.getKey()); }
        catch (Exception e) { throw new MoneroError(e); }
        break;
      }
    }
    if (!found) throw new RuntimeException("MoneroWalletRpc instance not associated with port");
  }
  
  private int registerPort() throws IOException {
    
    // register next consecutive port
    if (startPort != null) {
      int port = startPort;
      while (registeredPorts.containsKey(port)) port++;
      registeredPorts.put(port, new RegisteredPort());
      return port;
    }
    
    // register auto-assigned port
    else {
      RegisteredPort registeredPort = new RegisteredPort();
      registeredPort.socket = new ServerSocket(0); // reserve local port
      registeredPorts.put(registeredPort.socket.getLocalPort(), registeredPort);
      return registeredPort.socket.getLocalPort();
    }
  }
  
  private void unregisterPort(int port) throws IOException {
    RegisteredPort registeredPort = registeredPorts.get(port);
    if (registeredPort.socket != null) registeredPort.socket.close();
    registeredPorts.remove(port);
  }
  
  /*
   * Data associated with a registered port.
   */
  private class RegisteredPort {
    public MoneroWalletRpc walletRpc;
    public ServerSocket socket;
  }
}
