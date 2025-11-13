import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;

/**
 * Cliente de Chat com Interface Gráfica - Conecta ao servidor de chat
 * e fornece interface amigável para comunicação.
 * 
 * Funcionalidades:
 * - Interface gráfica com Swing
 * - Duas threads: GUI e recebimento de mensagens
 * - Formatação amigável de mensagens do servidor
 * - Mecanismo de escape para comandos não reconhecidos
 * - Suporte a linha de comando e modo GUI
 * 
 * @author Ana Beatriz Carvalho Duarte  
 * @author Eduardo Fernando Gonçalves Henriques
 */
public class ChatClient {
  // =============================================
  // CONSTANTES E CONFIGURAÇÕES
  // =============================================
  
  /** Tamanho do buffer de leitura (1KB) */
  private static final int BUFFER_SIZE = 1024;
  
  /** Codificação/Decodificação UTF-8 */
  private static final Charset charset = Charset.forName("UTF-8");
  private static final CharsetEncoder encoder = charset.newEncoder();
  private static final CharsetDecoder decoder = charset.newDecoder();
  
  // =============================================
  // VARIÁVEIS DE INSTÂNCIA - COMUNICAÇÃO
  // =============================================
  
  /** Canal de comunicação com o servidor */
  private SocketChannel channel;
  
  /** Buffer para dados recebidos */
  private ByteBuffer readBuffer = ByteBuffer.allocate(BUFFER_SIZE);
  
  /** Buffer para mensagens parciais recebidas */
  private StringBuilder partialMessage = new StringBuilder();
  
  // =============================================
  // VARIÁVEIS DE INSTÂNCIA - INTERFACE GRÁFICA
  // =============================================
  
  /** Janela principal da aplicação */
  private JFrame frame;
  
  /** Área de texto para exibir mensagens do chat */
  private JTextArea chatArea;
  
  /** Campo de texto para digitar mensagens */
  private JTextField inputField;
  
  /** Campo para endereço do servidor */
  private JTextField serverField;
  
  /** Campo para número da porta */
  private JTextField portField;
  
  /** Botão para conectar ao servidor */
  private JButton connectButton;
  
  /** Botão para desconectar do servidor */
  private JButton disconnectButton;
  
  /** Flag indicando se está conectado ao servidor */
  private boolean connected = false;
  
  // =============================================
  // CONSTRUTOR E INICIALIZAÇÃO
  // =============================================
  
  /**
   * Construtor - inicializa a interface gráfica.
   */
  public ChatClient() {
    createGUI();
  }
  
  /**
   * Cria e configura todos os componentes da interface gráfica.
   * Layout:
   * - Norte: Painel de conexão (servidor, porta, botões)
   * - Centro: Área de chat com scroll
   * - Sul: Campo de entrada de mensagens
   */
  private void createGUI() {
    // =============================================
    // CONFIGURAÇÃO DA JANELA PRINCIPAL
    // =============================================
    
    frame = new JFrame("Chat Client");
    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    frame.setLayout(new BorderLayout());
    
    // =============================================
    // PAINEL DE CONEXÃO (NORTE)
    // =============================================
    
    JPanel connectionPanel = new JPanel(new FlowLayout());
    
    // Campo do servidor
    connectionPanel.add(new JLabel("Servidor:"));
    serverField = new JTextField("localhost", 10);
    connectionPanel.add(serverField);
    
    // Campo da porta
    connectionPanel.add(new JLabel("Porta:"));
    portField = new JTextField("8000", 5);
    connectionPanel.add(portField);
    
    // Botões de conexão
    connectButton = new JButton("Conectar");
    disconnectButton = new JButton("Desconectar");
    disconnectButton.setEnabled(false); // Inicialmente desabilitado
    
    // Ações dos botões
    connectButton.addActionListener(e -> connect());
    disconnectButton.addActionListener(e -> disconnect());
    
    connectionPanel.add(connectButton);
    connectionPanel.add(disconnectButton);
    
    frame.add(connectionPanel, BorderLayout.NORTH);
    
    // =============================================
    // ÁREA DE CHAT (CENTRO)
    // =============================================
    
    chatArea = new JTextArea(20, 50);
    chatArea.setEditable(false); // Somente leitura
    // Adiciona scroll para muitas mensagens
    JScrollPane scrollPane = new JScrollPane(chatArea);
    frame.add(scrollPane, BorderLayout.CENTER);
    
    // =============================================
    // CAMPO DE ENTRADA (SUL)
    // =============================================
    
    inputField = new JTextField();
    inputField.setEnabled(false); // Habilitado apenas quando conectado
    // Envia mensagem ao pressionar Enter
    inputField.addActionListener(e -> sendMessage());
    
    frame.add(inputField, BorderLayout.SOUTH);
    
    // =============================================
    // FINALIZAÇÃO DA JANELA
    // =============================================
    
    frame.pack(); // Ajusta tamanho aos componentes
    frame.setVisible(true); // Torna visível
  }
  
  // =============================================
  // MÉTODOS DE CONEXÃO E DESCONEXÃO
  // =============================================
  
  /**
   * Estabelece conexão com o servidor de chat.
   * Valida entrada, cria canal e inicia thread de recebimento.
   */
  private void connect() {
    String server = serverField.getText();
    int port;
    
    // Validação da porta
    try {
      port = Integer.parseInt(portField.getText());
    } catch (NumberFormatException e) {
      showError("Porta inválida");
      return;
    }
    
    try {
      // =============================================
      // CRIAÇÃO E CONEXÃO DO CANAL
      // =============================================
      
      channel = SocketChannel.open();
      channel.configureBlocking(true); // Modo bloqueante para simplicidade
      
      // Conecta ao servidor (com timeout implícito)
      channel.connect(new InetSocketAddress(server, port));
      
      // =============================================
      // ATUALIZAÇÃO DA INTERFACE
      // =============================================
      
      connected = true;
      connectButton.setEnabled(false);
      disconnectButton.setEnabled(true);
      inputField.setEnabled(true); // Habilita envio de mensagens
      serverField.setEnabled(false); // Desabilita mudanças durante conexão
      portField.setEnabled(false);
      
      addToChat("Conectado ao servidor " + server + ":" + port);
      
      // =============================================
      // INICIALIZAÇÃO DA THREAD DE RECEBIMENTO
      // =============================================
      
      // Thread separada para não bloquear a GUI
      new Thread(this::receiveMessages).start();
      
    } catch (Exception e) {
      showError("Erro ao conectar: " + e.getMessage());
    }
  }
  
  /**
   * Desconecta graciosamente do servidor.
   * Envia comando /bye, fecha canal e restaura interface.
   */
  private void disconnect() {
    connected = false;
    
    try {
      // Envia comando de desconexão antes de fechar
      if (channel != null) {
        sendToServer("/bye");
        channel.close();
      }
    } catch (IOException e) {
      // Ignora erros durante desconexão
    }
    
    // =============================================
    // RESTAURAÇÃO DA INTERFACE
    // =============================================
    
    connectButton.setEnabled(true);
    disconnectButton.setEnabled(false);
    inputField.setEnabled(false);
    serverField.setEnabled(true);
    portField.setEnabled(true);
    
    addToChat("Desconectado do servidor");
  }
  
  // =============================================
  // MÉTODOS DE ENVIO DE MENSAGENS
  // =============================================
  
  /**
   * Processa e envia mensagem digitada pelo usuário.
   * Aplica mecanismo de escape para comandos não reconhecidos.
   */
  private void sendMessage() {
    if (!connected) return;
    
    String message = inputField.getText().trim();
    if (message.isEmpty()) return;
    
    inputField.setText(""); // Limpa campo após enviar
    
    // =============================================
    // MECANISMO DE ESCAPE - Conforme especificação
    // =============================================
    
    String processedMessage;
    
    if (message.startsWith("/nick ") || message.startsWith("/join ") || 
        message.startsWith("/leave") || message.startsWith("/bye") ||
        message.startsWith("/priv ")) {
      // Comando válido - envia normalmente
      processedMessage = message;
    } else if (message.startsWith("//")) {
      // Já está com escape - envia normalmente  
      processedMessage = message;
    } else if (message.startsWith("/")) {
      // Não é comando conhecido - aplica escape
      processedMessage = "/" + message;
    } else {
      // Mensagem normal - envia sem alteração
      processedMessage = message;
    }
    
    sendToServer(processedMessage);
  }
  
  /**
   * Envia mensagem raw para o servidor.
   * Codifica string para bytes e escreve no canal.
   * 
   * @param message Mensagem a enviar (sem \n no final)
   */
  private void sendToServer(String message) {
    if (!connected || channel == null) return;
    
    try {
      // Codifica mensagem + \n para bytes
      ByteBuffer buffer = encoder.encode(CharBuffer.wrap(message + "\n"));
      
      // Escreve todos os bytes no canal
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
    } catch (IOException e) {
      addToChat("Erro ao enviar mensagem: " + e.getMessage());
      disconnect();
    }
  }
  
  // =============================================
  // MÉTODOS DE RECEBIMENTO DE MENSAGENS
  // =============================================
  
  /**
   * Thread de recebimento de mensagens do servidor.
   * Executa em loop enquanto conectado, lendo e processando mensagens.
   */
  private void receiveMessages() {
    readBuffer.clear();
    
    // Loop principal de recebimento
    while (connected && channel != null) {
      try {
        int bytesRead = channel.read(readBuffer);
        
        // Servidor fechou conexão
        if (bytesRead == -1) {
          SwingUtilities.invokeLater(() -> {
            addToChat("Servidor desconectou");
            disconnect();
          });
          break;
        }
        
        // Processa dados recebidos
        if (bytesRead > 0) {
          readBuffer.flip();
          String data = decoder.decode(readBuffer).toString();
          readBuffer.clear();
          
          // Adiciona aos dados parciais
          partialMessage.append(data);
          
          // Processa linhas completas
          String message = partialMessage.toString();
          int lineEnd;
          
          while ((lineEnd = message.indexOf('\n')) != -1) {
            String line = message.substring(0, lineEnd).trim();
            
            // Processa apenas linhas não vazias
            if (line.length() > 0) {
              processServerMessage(line);
            }
            
            message = message.substring(lineEnd + 1);
          }
          
          partialMessage = new StringBuilder(message);
        }
        
        // Pequena pausa para evitar uso excessivo de CPU
        Thread.sleep(10);
        
      } catch (Exception e) {
        // Em caso de erro, desconecta se ainda estava conectado
        if (connected) {
          SwingUtilities.invokeLater(() -> {
            addToChat("Erro na conexão: " + e.getMessage());
            disconnect();
          });
        }
        break;
      }
    }
  }
  
  /**
   * Processa e formata mensagem recebida do servidor.
   * Converte formato do protocolo para exibição amigável (+5% bônus).
   * Executa na EDT (Event Dispatch Thread) do Swing para segurança.
   * 
   * @param message Mensagem raw do servidor
   */
  private void processServerMessage(String message) {
    // Garante execução na thread da GUI
    SwingUtilities.invokeLater(() -> {
      // =============================================
      // PROCESSAMENTO POR TIPO DE MENSAGEM
      // =============================================
      
      if (message.startsWith("MESSAGE ")) {
        // Formato: MESSAGE nick mensagem
        int firstSpace = message.indexOf(' ');
        int secondSpace = message.indexOf(' ', firstSpace + 1);
        
        if (secondSpace != -1) {
          String sender = message.substring(firstSpace + 1, secondSpace);
          String msg = message.substring(secondSpace + 1);
          // Formatação amigável: nick: mensagem
          addToChat(sender + ": " + msg);
        }
        
      } else if (message.startsWith("NEWNICK ")) {
        // Formato: NEWNICK antigo novo
        String[] parts = message.split("\\s+", 3);
        if (parts.length >= 3) {
          // Formatação amigável: antigo mudou de nome para novo
          addToChat(parts[1] + " mudou de nome para " + parts[2]);
        }
        
      } else if (message.startsWith("JOINED ")) {
        // Formato: JOINED nick
        String nick = message.substring(7);
        // Formatação amigável: nick entrou na sala
        addToChat(nick + " entrou na sala");
        
      } else if (message.startsWith("LEFT ")) {
        // Formato: LEFT nick  
        String nick = message.substring(5);
        // Formatação amigável: nick saiu da sala
        addToChat(nick + " saiu da sala");
        
      } else if (message.startsWith("PRIVATE ")) {
        // Formato: PRIVATE remetente mensagem
        String[] parts = message.split("\\s+", 3);
        if (parts.length >= 3) {
          // Formatação amigável: [PRIVADO de remetente]: mensagem
          addToChat("[PRIVADO de " + parts[1] + "]: " + parts[2]);
        }
        
      } else if (message.equals("OK")) {
        // Confirmação de comando - não exibe nada
        // (comando bem-sucedido)
        
      } else if (message.startsWith("ERROR")) {
        // Formato: ERROR [mensagem]
        String errorMsg = message.length() > 6 ? message.substring(6) : "Erro desconhecido";
        addToChat("ERRO: " + errorMsg);
        
      } else if (message.equals("BYE")) {
        // Confirmação de desconexão
        addToChat("Adeus!");
        disconnect();
        
      } else {
        // Mensagem não reconhecida
        addToChat("Mensagem desconhecida: " + message);
      }
    });
  }
  
  // =============================================
  // MÉTODOS AUXILIARES DA INTERFACE
  // =============================================
  
  /**
   * Adiciona mensagem formatada à área de chat.
   * Garante scroll automático para a mensagem mais recente.
   * 
   * @param message Mensagem a ser exibida
   */
  private void addToChat(String message) {
    chatArea.append(message + "\n");
    // Scroll automático para o final
    chatArea.setCaretPosition(chatArea.getDocument().getLength());
  }
  
  /**
   * Exibe diálogo de erro modal.
   * 
   * @param message Mensagem de erro
   */
  private void showError(String message) {
    JOptionPane.showMessageDialog(frame, message, "Erro", JOptionPane.ERROR_MESSAGE);
  }
  
  // =============================================
  // MÉTODO PRINCIPAL
  // =============================================
  
  /**
   * Ponto de entrada do cliente de chat.
   * Suporta dois modos de operação:
   * - Linha de comando: java ChatClient servidor porta
   * - Modo GUI: java ChatClient (sem argumentos)
   * 
   * @param args Argumentos da linha de comando
   */
  public static void main(String[] args) {
    // Garante execução na EDT do Swing
    SwingUtilities.invokeLater(() -> {
      if (args.length == 2) {
        // =============================================
        // MODO LINHA DE COMANDO
        // =============================================
        
        String server = args[0];
        int port;
        
        try {
          port = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
          System.out.println("Porta inválida: " + args[1]);
          return;
        }
        
        // Cria cliente e preenche campos automaticamente
        ChatClient client = new ChatClient();
        client.serverField.setText(server);
        client.portField.setText(String.valueOf(port));
        client.connect(); // Conecta automaticamente
        
      } else if (args.length > 0) {
        // Argumentos inválidos
        System.out.println("Uso: java ChatClient <servidor> <porta>");
        System.exit(1);
        
      } else {
        // =============================================
        // MODO GUI NORMAL (sem argumentos)
        // =============================================
        
        new ChatClient();
      }
    });
  }
}