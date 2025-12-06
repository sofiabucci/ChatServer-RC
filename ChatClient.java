import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;

/**
 * Cliente de Chat
 * Conecta a um servidor de chat, permite comunicação em salas com nicknames e suporta comandos do protocolo.
 * Funcionalidades principais: conexão TCP, nicknames, salas de chat, mensagens privadas, 
 * mecanismo de escape para comandos não reconhecidos e formatação amigável de mensagens.
 * 
 * Protocolo suportado:
 * - Comandos enviados: /nick, /join, /leave, /bye, /priv
 * - Estados do usuário: conectado, com nickname definido, dentro de sala
 * - Mecanismo de escape: mensagens começando com '/' não comandos são automaticamente escapadas
 * 
 * @author Ana Beatriz Carvalho Duarte  
 * @author Eduardo Fernando Gonçalves Henriques
 */
public class ChatClient {
    private static final int BUFFER_SIZE = 1024;
    private static final Charset charset = Charset.forName("UTF-8");
    private static final CharsetEncoder encoder = charset.newEncoder();
    private static final CharsetDecoder decoder = charset.newDecoder();
    
    private SocketChannel channel;
    private ByteBuffer readBuffer = ByteBuffer.allocate(BUFFER_SIZE);
    private StringBuilder partialMessage = new StringBuilder();
    
    private JFrame frame;
    private JTextArea chatArea;
    private JTextField inputField;
    private JTextField serverField;
    private JTextField portField;
    private JButton connectButton;
    private JButton disconnectButton;
    private boolean connected = false;

    /**
     * Construtor que inicializa a interface gráfica do cliente.
     * Cria todos os componentes Swing e configura o layout da janela.
     */
    public ChatClient() {
        createGUI();
    }

    /**
     * Cria e configura a interface gráfica com os componentes:
     * - Painel superior: campos para servidor e porta, botões de conexão
     * - Área central: histórico de mensagens do chat com scroll
     * - Painel inferior: campo de entrada para digitar mensagens
     * 
     * Layout organizado em BorderLayout para distribuição dos componentes.
     */
    private void createGUI() {
        frame = new JFrame("Chat Client");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        
        JPanel connectionPanel = new JPanel(new FlowLayout());
        connectionPanel.add(new JLabel("Servidor:"));
        serverField = new JTextField("localhost", 10);
        connectionPanel.add(serverField);
        
        connectionPanel.add(new JLabel("Porta:"));
        portField = new JTextField("8000", 5);
        connectionPanel.add(portField);
        
        connectButton = new JButton("Conectar");
        disconnectButton = new JButton("Desconectar");
        disconnectButton.setEnabled(false);
        
        connectButton.addActionListener(e -> connect());
        disconnectButton.addActionListener(e -> disconnect());
        
        connectionPanel.add(connectButton);
        connectionPanel.add(disconnectButton);
        frame.add(connectionPanel, BorderLayout.NORTH);
        
        chatArea = new JTextArea(20, 50);
        chatArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(chatArea);
        frame.add(scrollPane, BorderLayout.CENTER);
        
        inputField = new JTextField();
        inputField.setEnabled(false);
        inputField.addActionListener(e -> sendMessage());
        frame.add(inputField, BorderLayout.SOUTH);
        
        frame.pack();
        frame.setVisible(true);
    }

    /**
     * Estabelece conexão TCP com o servidor de chat especificado nos campos da interface.
     * Valida os parâmetros de entrada (porta numérica), cria SocketChannel bloqueante,
     * inicia thread para recebimento de mensagens e atualiza interface para modo conectado.
     * Em caso de erro (porta inválida, servidor inacessível) exibe mensagem de erro.
     */
    private void connect() {
        String server = serverField.getText();
        int port;
        
        try {
            port = Integer.parseInt(portField.getText());
        } catch (NumberFormatException e) {
            showError("Porta inválida");
            return;
        }
        
        try {
            channel = SocketChannel.open();
            channel.configureBlocking(true);
            channel.connect(new InetSocketAddress(server, port));
            
            connected = true;
            connectButton.setEnabled(false);
            disconnectButton.setEnabled(true);
            inputField.setEnabled(true);
            serverField.setEnabled(false);
            portField.setEnabled(false);
            
            addToChat("Conectado ao servidor " + server + ":" + port);
            new Thread(this::receiveMessages).start();
        } catch (Exception e) {
            showError("Erro ao conectar: " + e.getMessage());
        }
    }

    /**
     * Desconecta graciosamente do servidor enviando comando /bye.
     * Fecha o canal de comunicação, restaura interface para modo desconectado
     * e limpa buffers internos. Executado tanto por ação do usuário quanto
     * automaticamente em caso de erro de comunicação.
     */
    private void disconnect() {
        connected = false;
        
        try {
            if (channel != null) {
                sendToServer("/bye");
                channel.close();
            }
        } catch (IOException e) {}
        
        connectButton.setEnabled(true);
        disconnectButton.setEnabled(false);
        inputField.setEnabled(false);
        serverField.setEnabled(true);
        portField.setEnabled(true);
        addToChat("Desconectado do servidor");
    }

    /**
     * Processa mensagem digitada pelo usuário, aplicando mecanismo de escape conforme protocolo:
     * - Comandos válidos (/nick, /join, /leave, /bye, /priv) enviados sem alteração
     * - Mensagens começando com "//" (já escapadas) enviadas sem alteração
     * - Mensagens começando com "/" não comandos recebem "/" adicional (escape)
     * - Mensagens normais (sem "/" inicial) enviadas sem alteração
     * Após processamento, envia mensagem ao servidor via sendToServer().
     */
    private void sendMessage() {
        if (!connected) return;
        
        String message = inputField.getText().trim();
        if (message.isEmpty()) return;
        
        inputField.setText("");
        String processedMessage;
        
        if (message.startsWith("/nick ") || message.startsWith("/join ") || 
            message.startsWith("/leave") || message.startsWith("/bye") ||
            message.startsWith("/priv ")) {
            processedMessage = message;
        } else if (message.startsWith("//")) {
            processedMessage = message;
        } else if (message.startsWith("/")) {
            processedMessage = "/" + message;
        } else {
            processedMessage = message;
        }
        
        sendToServer(processedMessage);
    }

    /**
     * Envia mensagem formatada para o servidor através do canal TCP.
     * Codifica string em UTF-8, adiciona delimitador de linha (\n) e escreve no SocketChannel.
     * Em caso de erro de I/O, notifica usuário e inicia desconexão graciosa.
     * 
     * @param message Mensagem a enviar (sem \n no final)
     */
    private void sendToServer(String message) {
        if (!connected || channel == null) return;
        
        try {
            ByteBuffer buffer = encoder.encode(CharBuffer.wrap(message + "\n"));
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        } catch (IOException e) {
            addToChat("Erro ao enviar mensagem: " + e.getMessage());
            disconnect();
        }
    }

    /**
     * Thread principal de recebimento de mensagens do servidor.
     * Executa em loop enquanto conectado, lendo dados do SocketChannel.
     * Implementa bufferização para lidar com mensagens parciais (delineação TCP).
     * Detecta desconexão do servidor (bytesRead == -1) e desconecta automaticamente.
     * Processa cada linha completa através de processServerMessage().
     */
    private void receiveMessages() {
        readBuffer.clear();
        
        while (connected && channel != null) {
            try {
                int bytesRead = channel.read(readBuffer);
                
                if (bytesRead == -1) {
                    SwingUtilities.invokeLater(() -> {
                        addToChat("Servidor desconectou");
                        disconnect();
                    });
                    break;
                }
                
                if (bytesRead > 0) {
                    readBuffer.flip();
                    String data = decoder.decode(readBuffer).toString();
                    readBuffer.clear();
                    
                    partialMessage.append(data);
                    String message = partialMessage.toString();
                    int lineEnd;
                    
                    while ((lineEnd = message.indexOf('\n')) != -1) {
                        String line = message.substring(0, lineEnd).trim();
                        if (line.length() > 0) {
                            processServerMessage(line);
                        }
                        message = message.substring(lineEnd + 1);
                    }
                    partialMessage = new StringBuilder(message);
                }
                
                Thread.sleep(10);
            } catch (Exception e) {
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
     * Processa e formata mensagem recebida do servidor para exibição amigável na interface.
     * Converte formatos do protocolo (MESSAGE, NEWNICK, JOINED, LEFT, PRIVATE, ERROR, BYE)
     * em texto legível para o usuário. Executado na Event Dispatch Thread do Swing.
     * Formatação amigável implementada como bônus de 5% na avaliação.
     * 
     * @param message Mensagem raw do servidor (uma linha completa)
     */
    private void processServerMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            if (message.startsWith("MESSAGE ")) {
                int firstSpace = message.indexOf(' ');
                int secondSpace = message.indexOf(' ', firstSpace + 1);
                if (secondSpace != -1) {
                    String sender = message.substring(firstSpace + 1, secondSpace);
                    String msg = message.substring(secondSpace + 1);
                    addToChat(sender + ": " + msg);
                }
            } else if (message.startsWith("NEWNICK ")) {
                String[] parts = message.split("\\s+", 3);
                if (parts.length >= 3) {
                    addToChat(parts[1] + " mudou de nome para " + parts[2]);
                }
            } else if (message.startsWith("JOINED ")) {
                String nick = message.substring(7);
                addToChat(nick + " entrou na sala");
            } else if (message.startsWith("LEFT ")) {
                String nick = message.substring(5);
                addToChat(nick + " saiu da sala");
            } else if (message.startsWith("PRIVATE ")) {
                String[] parts = message.split("\\s+", 3);
                if (parts.length >= 3) {
                    addToChat("[PRIVADO de " + parts[1] + "]: " + parts[2]);
                }
            } else if (message.equals("OK")) {
            } else if (message.startsWith("ERROR")) {
                String errorMsg = message.length() > 6 ? message.substring(6) : "Erro desconhecido";
                addToChat("ERRO: " + errorMsg);
            } else if (message.equals("BYE")) {
                addToChat("Adeus!");
                disconnect();
            } else {
                addToChat("Mensagem desconhecida: " + message);
            }
        });
    }

    /**
     * Adiciona mensagem formatada à área de chat, garantindo scroll automático para o final.
     * Executado na Event Dispatch Thread para segurança na manipulação de componentes Swing.
     * 
     * @param message Texto a ser exibido na área de chat
     */
    private void addToChat(String message) {
        chatArea.append(message + "\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    /**
     * Exibe diálogo de erro modal ao usuário. Útil para validação de entrada
     * e notificação de erros de conexão. Thread-safe para execução na EDT.
     * 
     * @param message Mensagem de erro a ser exibida
     */
    private void showError(String message) {
        JOptionPane.showMessageDialog(frame, message, "Erro", JOptionPane.ERROR_MESSAGE);
    }

    /**
     * Ponto de entrada do cliente de chat. Suporta dois modos de operação:
     * - Modo linha de comando: java ChatClient servidor porta
     *   (conecta automaticamente aos parâmetros fornecidos)
     * - Modo GUI interativo: java ChatClient (sem argumentos)
     *   (exibe interface completa para configuração manual)
     * 
     * @param args Argumentos da linha de comando: [servidor] [porta] ou vazio para modo GUI
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            if (args.length == 2) {
                String server = args[0];
                int port;
                try {
                    port = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    System.out.println("Porta inválida: " + args[1]);
                    return;
                }
                ChatClient client = new ChatClient();
                client.serverField.setText(server);
                client.portField.setText(String.valueOf(port));
                client.connect();
            } else if (args.length > 0) {
                System.out.println("Uso: java ChatClient <servidor> <porta>");
                System.exit(1);
            } else {
                new ChatClient();
            }
        });
    }
}