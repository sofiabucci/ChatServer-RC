import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Servidor de Chat Multiplex - Implementa um servidor de chat multiusuário
 * usando NIO (Non-blocking I/O) para suportar múltiplos clientes simultaneamente.
 * 
 * Funcionalidades:
 * - Gerencia salas de chat
 * - Controla estados dos usuários (init, outside, inside)
 * - Processa comandos: /nick, /join, /leave, /bye, /priv
 * - Sistema de broadcast para mensagens em sala
 * - Mecanismo de escape para mensagens que começam com '/'
 * 
 */
public class ChatServer
{
  // =============================================
  // CONSTANTES E CONFIGURAÇÕES
  // =============================================
  
  /** Buffer pré-alocado para dados recebidos (16KB) */
  static private final ByteBuffer buffer = ByteBuffer.allocate(16384);
  
  /** Codificação/Decodificação UTF-8 para texto */
  static private final Charset charset = Charset.forName("UTF8");
  static private final CharsetDecoder decoder = charset.newDecoder();
  static private final CharsetEncoder encoder = charset.newEncoder();

  // =============================================
  // ESTRUTURAS DE DADOS PARA GERENCIAMENTO DE ESTADO
  // =============================================
  
  /** Mapa: nick -> ClientState (controla nicknames únicos) */
  static private Map<String, ClientState> clients = new ConcurrentHashMap<>();
  
  /** Mapa: sala -> conjunto de ClientState (gerencia usuários por sala) */
  static private Map<String, Set<ClientState>> rooms = new ConcurrentHashMap<>();
  
  /** Mapa: SocketChannel -> ClientState (relaciona canal com estado do cliente) */
  static private Map<SocketChannel, ClientState> channelToClient = new ConcurrentHashMap<>();
  
  /** Mapa: SocketChannel -> StringBuilder (buffer para mensagens parciais) */
  static private Map<SocketChannel, StringBuilder> partialMessages = new ConcurrentHashMap<>();

  // =============================================
  // MÉTODO PRINCIPAL
  // =============================================
  
  /**
   * Ponto de entrada do servidor de chat.
   * Inicializa o servidor na porta especificada e inicia o loop de eventos.
   * 
   * @param args Argumentos da linha de comando: [porta]
   * @throws Exception Em caso de erro de inicialização
   */
  static public void main(String args[]) throws Exception {
    // Validação e parse dos argumentos
    if (args.length != 1) {
      System.out.println("Uso: java ChatServer <porta>");
      return;
    }
    
    int port = Integer.parseInt(args[0]);
    
    try {
      // =============================================
      // INICIALIZAÇÃO DO SERVER SOCKET CHANNEL
      // =============================================
      
      // Cria e configura o ServerSocketChannel como não-bloqueante
      ServerSocketChannel ssc = ServerSocketChannel.open();
      ssc.configureBlocking(false);

      // Obtém o Socket associado e faz bind na porta especificada
      ServerSocket ss = ssc.socket();
      InetSocketAddress isa = new InetSocketAddress(port);
      ss.bind(isa);

      // =============================================
      // CONFIGURAÇÃO DO SELECTOR PARA MULTIPLEXAÇÃO
      // =============================================
      
      // Cria selector para monitorar múltiplos canais
      Selector selector = Selector.open();
      
      // Registra o servidor para aceitar conexões
      ssc.register(selector, SelectionKey.OP_ACCEPT);
      System.out.println("Listening on port " + port);

      // =============================================
      // LOOP PRINCIPAL DE PROCESSAMENTO DE EVENTOS
      // =============================================
      
      while (true) {
        // Aguarda por atividade (conexões ou dados)
        int num = selector.select();

        // Se não houve atividade, continua esperando
        if (num == 0) {
          continue;
        }

        // Processa todas as chaves com atividade detectada
        Set<SelectionKey> keys = selector.selectedKeys();
        Iterator<SelectionKey> it = keys.iterator();
        
        while (it.hasNext()) {
          SelectionKey key = it.next();
          
          // Remove a chave do conjunto para processamento único
          it.remove();

          if (!key.isValid()) continue;

          // =============================================
          // TRATAMENTO DE NOVAS CONEXÕES
          // =============================================
          
          if (key.isAcceptable()) {
            // Aceita nova conexão
            Socket s = ss.accept();
            System.out.println("Got connection from " + s);

            // Configura o canal do cliente como não-bloqueante
            SocketChannel sc = s.getChannel();
            sc.configureBlocking(false);

            // Registra o canal para leitura no selector
            sc.register(selector, SelectionKey.OP_READ);

            // Inicializa estado do cliente
            ClientState state = new ClientState();
            state.channel = sc;
            state.state = "init"; // Estado inicial: sem nickname
            channelToClient.put(sc, state);
            partialMessages.put(sc, new StringBuilder());

          } 
          // =============================================
          // TRATAMENTO DE DADOS RECEBIDOS
          // =============================================
          
          else if (key.isReadable()) {
            SocketChannel sc = null;

            try {
              sc = (SocketChannel) key.channel();
              
              // Processa entrada do cliente
              boolean ok = processInput(sc);

              // Se conexão foi fechada ou erro, remove o cliente
              if (!ok) {
                key.cancel();
                Socket s = null;
                
                try {
                  s = sc.socket();
                  System.out.println("Closing connection to " + s);
                  disconnectClient(sc, channelToClient.get(sc));
                  s.close();
                } catch (IOException ie) {
                  System.err.println("Error closing socket " + s + ": " + ie);
                }
              }

            } catch (IOException ie) {
              // Em caso de exceção, remove o canal do selector
              key.cancel();
              
              try {
                disconnectClient(sc, channelToClient.get(sc));
                sc.close();
              } catch (IOException ie2) {
                System.out.println(ie2);
              }

              System.out.println("Closed " + sc);
            }
          }
        }
      }
    } catch (IOException ie) {
      System.err.println(ie);
    }
  }

  // =============================================
  // MÉTODOS DE PROCESSAMENTO DE ENTRADA
  // =============================================
  
  /**
   * Processa dados recebidos de um cliente.
   * Lê dados do canal, decodifica e processa mensagens completas.
   * 
   * @param sc SocketChannel do cliente
   * @return true se conexão deve permanecer, false para fechar
   * @throws IOException Em caso de erro de I/O
   */
  static private boolean processInput(SocketChannel sc) throws IOException {
    // Limpa e lê dados para o buffer
    buffer.clear();
    int bytesRead = sc.read(buffer);
    
    // Se cliente fechou conexão, retorna false
    if (bytesRead == -1) {
      return false;
    }

    buffer.flip();

    // Se não há dados mas conexão está aberta, retorna true
    if (buffer.limit() == 0) {
      return true;
    }

    // Decodifica dados recebidos para string
    String data = decoder.decode(buffer).toString();
    
    // Obtém buffer de mensagens parciais e estado do cliente
    StringBuilder partialMessage = partialMessages.get(sc);
    ClientState state = channelToClient.get(sc);
    
    // Inicializa buffer se necessário
    if (partialMessage == null) {
      partialMessage = new StringBuilder();
      partialMessages.put(sc, partialMessage);
    }
    
    // Adiciona dados recebidos ao buffer parcial
    partialMessage.append(data);
    
    // Processa linhas completas (separadas por \n)
    String message = partialMessage.toString();
    int lineEnd;
    
    while ((lineEnd = message.indexOf('\n')) != -1) {
      String line = message.substring(0, lineEnd).trim();
      
      // Processa apenas linhas não vazias
      if (line.length() > 0) {
        processMessage(state, line);
      }
      
      message = message.substring(lineEnd + 1);
    }
    
    // Atualiza buffer com mensagem parcial restante
    partialMessages.put(sc, new StringBuilder(message));

    return true;
  }

  /**
   * Processa uma mensagem completa do cliente.
   * Identifica se é comando ou mensagem normal e delega para handler apropriado.
   * 
   * @param state Estado do cliente remetente
   * @param message Mensagem recebida (já trimada)
   */
  static private void processMessage(ClientState state, String message) {
    System.out.println("Processando: '" + message + "' do estado: " + state.state);
    
    // =============================================
    // IDENTIFICAÇÃO DO TIPO DE MENSAGEM
    // =============================================
    
    if (message.startsWith("/")) {
      // É um comando - divide em partes
      String[] parts = message.split("\\s+", 3);
      String command = parts[0];
      
      // Delega para handler específico baseado no comando
      switch (command) {
        case "/nick":
          if (parts.length >= 2) {
            handleNick(state, parts[1]);
          } else {
            sendError(state, "Nome inválido");
          }
          break;
          
        case "/join":
          if (parts.length >= 2) {
            handleJoin(state, parts[1]);
          } else {
            sendError(state, "Sala inválida");
          }
          break;
          
        case "/leave":
          handleLeave(state);
          break;
          
        case "/bye":
          handleBye(state);
          break;
          
        case "/priv":
          if (parts.length >= 3) {
            handlePrivate(state, parts[1], parts[2]);
          } else {
            sendError(state, "Uso: /priv nome mensagem");
          }
          break;
          
        default:
          // Comando não reconhecido - trata como mensagem se estiver em sala
          if (state.state.equals("inside")) {
            // Aplica mecanismo de escape: remove '/' extra se houver
            String processedMessage = message;
            if (message.startsWith("//")) {
              processedMessage = message.substring(1);
            }
            broadcastMessage(state, processedMessage);
          } else {
            sendError(state, "Comando não suportado");
          }
      }
    } else {
      // É uma mensagem normal - verifica se pode enviar
      if (state.state.equals("inside")) {
        broadcastMessage(state, message);
      } else {
        sendError(state, "Não está numa sala");
      }
    }
  }

  // =============================================
  // HANDLERS DE COMANDOS
  // =============================================
  
  /**
   * Handler do comando /nick - Define ou altera nickname do usuário.
   * Estados válidos: init, outside, inside
   * 
   * @param state Estado do cliente
   * @param newNick Novo nickname desejado
   */
  static private void handleNick(ClientState state, String newNick) {
    // Verifica disponibilidade do nickname
    if (!isNickAvailable(newNick)) {
      sendError(state, "Nome já em uso");
      return;
    }
    
    String oldNick = state.nick;
    
    // Estado INIT: primeiro nickname
    if (state.state.equals("init")) {
      state.nick = newNick;
      state.state = "outside"; // Transição: init → outside
      clients.put(newNick, state);
      sendOk(state);
    } 
    // Estados OUTSIDE ou INSIDE: mudança de nickname
    else if (state.state.equals("outside") || state.state.equals("inside")) {
      state.nick = newNick;
      clients.remove(oldNick);
      clients.put(newNick, state);
      
      // Se está em sala, notifica outros usuários
      if (state.state.equals("inside")) {
        broadcastToRoom(state.room, "NEWNICK " + oldNick + " " + newNick, state);
      }
      sendOk(state);
    }
  }

  /**
   * Handler do comando /join - Entra ou cria uma sala.
   * Estados válidos: outside, inside
   * 
   * @param state Estado do cliente
   * @param room Nome da sala desejada
   */
  static private void handleJoin(ClientState state, String room) {
    // Verifica se comando é permitido no estado atual
    if (!state.state.equals("outside") && !state.state.equals("inside")) {
      sendError(state, "Comando não permitido neste estado");
      return;
    }
    
    // Se já está em sala, sai primeiro
    if (state.state.equals("inside")) {
      leaveRoom(state);
    }
    
    // Entra na nova sala
    joinRoom(state, room);
    sendOk(state);
  }

  /**
   * Handler do comando /leave - Sai da sala atual.
   * Estado válido: inside
   * 
   * @param state Estado do cliente
   */
  static private void handleLeave(ClientState state) {
    if (!state.state.equals("inside")) {
      sendError(state, "Não está numa sala");
      return;
    }
    
    leaveRoom(state);
    sendOk(state);
  }

  /**
   * Handler do comando /bye - Desconecta o cliente graciosamente.
   * Estados válidos: todos
   * 
   * @param state Estado do cliente
   */
  static private void handleBye(ClientState state) {
    // Sai da sala se estiver em uma
    if (state.state.equals("inside")) {
      leaveRoom(state);
    }
    
    // Envia confirmação e desconecta
    sendBye(state);
    disconnectClient(state.channel, state);
  }

  /**
   * Handler do comando /priv - Envia mensagem privada para usuário específico.
   * Funcionalidade extra (+10% na avaliação)
   * Estados válidos: outside, inside
   * 
   * @param state Estado do cliente remetente
   * @param targetNick Nickname do destinatário
   * @param message Mensagem a ser enviada
   */
  static private void handlePrivate(ClientState state, String targetNick, String message) {
    // Verifica se destinatário existe
    ClientState target = clients.get(targetNick);
    if (target == null) {
      sendError(state, "Utilizador não encontrado");
      return;
    }
    
    // Envia mensagem privada e confirma
    sendPrivate(target, state.nick, message);
    sendOk(state);
  }

  // =============================================
  // GERENCIAMENTO DE SALAS
  // =============================================
  
  /**
   * Adiciona usuário a uma sala.
   * Cria sala se não existir, notifica outros usuários.
   * 
   * @param state Estado do cliente
   * @param room Nome da sala
   */
  static private void joinRoom(ClientState state, String room) {
    // Cria sala se não existe
    if (!rooms.containsKey(room)) {
      rooms.put(room, ConcurrentHashMap.newKeySet());
    }
    
    // Adiciona usuário à sala
    rooms.get(room).add(state);
    state.room = room;
    state.state = "inside"; // Transição: outside → inside
    
    // Notifica outros usuários na sala
    broadcastToRoom(room, "JOINED " + state.nick, state);
  }

  /**
   * Remove usuário de sua sala atual.
   * Notifica outros usuários, remove sala se ficar vazia.
   * 
   * @param state Estado do cliente
   */
  static private void leaveRoom(ClientState state) {
    if (state.room != null && rooms.containsKey(state.room)) {
      Set<ClientState> roomClients = rooms.get(state.room);
      roomClients.remove(state);
      
      // Remove sala se ficou vazia
      if (roomClients.isEmpty()) {
        rooms.remove(state.room);
      } else {
        // Notifica outros usuários sobre a saída
        broadcastToRoom(state.room, "LEFT " + state.nick, state);
      }
    }
    
    state.room = null;
    state.state = "outside"; // Transição: inside → outside
  }

  // =============================================
  // SISTEMA DE BROADCAST E MENSAGENS
  // =============================================
  
  /**
   * Transmite mensagem de texto para todos na sala do remetente.
   * Aplica mecanismo de escape se necessário.
   * 
   * @param sender Cliente remetente
   * @param message Mensagem a ser transmitida
   */
  static private void broadcastMessage(ClientState sender, String message) {
    if (sender.room != null && rooms.containsKey(sender.room)) {
      // Processa escape: //mensagem → /mensagem
      String processedMessage = message;
      if (message.startsWith("//")) {
        processedMessage = message.substring(1);
      }
      
      // Transmite mensagem formatada
      broadcastToRoom(sender.room, "MESSAGE " + sender.nick + " " + processedMessage, null);
    }
  }

  /**
   * Envia mensagem para todos os clientes em uma sala, exceto opcionalmente um.
   * 
   * @param room Sala de destino
   * @param message Mensagem formatada do protocolo
   * @param exclude Cliente a excluir (normalmente o remetente)
   */
  static private void broadcastToRoom(String room, String message, ClientState exclude) {
    if (rooms.containsKey(room)) {
      for (ClientState client : rooms.get(room)) {
        // Envia para todos exceto o excluído, se canal estiver aberto
        if (client != exclude && client.channel.isOpen()) {
          sendRaw(client, message);
        }
      }
    }
  }

  // =============================================
  // MÉTODOS DE ENVIO DE RESPOSTAS
  // =============================================
  
  /** Envia confirmação de sucesso para cliente */
  static private void sendOk(ClientState state) {
    sendRaw(state, "OK");
  }

  /** Envia mensagem de erro para cliente */
  static private void sendError(ClientState state, String error) {
    sendRaw(state, "ERROR " + error);
  }

  /** Envia confirmação de desconexão para cliente */
  static private void sendBye(ClientState state) {
    sendRaw(state, "BYE");
  }

  /** Envia mensagem privada para destinatário */
  static private void sendPrivate(ClientState target, String from, String message) {
    sendRaw(target, "PRIVATE " + from + " " + message);
  }

  /**
   * Envia mensagem raw (bruta) para um cliente.
   * Codifica string para bytes e escreve no canal.
   * 
   * @param state Estado do cliente destino
   * @param message Mensagem a enviar (sem \n no final)
   */
  static private void sendRaw(ClientState state, String message) {
    // Verifica se cliente e canal são válidos
    if (state == null || state.channel == null || !state.channel.isOpen()) {
      return;
    }
    
    try {
      // Codifica mensagem + \n para bytes
      ByteBuffer buffer = encoder.encode(CharBuffer.wrap(message + "\n"));
      
      // Escreve todos os bytes no canal
      while (buffer.hasRemaining()) {
        state.channel.write(buffer);
      }
    } catch (IOException e) {
      System.err.println("Erro ao enviar mensagem para " + state.nick + ": " + e.getMessage());
    }
  }

  // =============================================
  // GERENCIAMENTO DE CONEXÕES
  // =============================================
  
  /**
   * Limpeza ao desconectar cliente.
   * Remove de todas as estruturas de dados.
   * 
   * @param channel Canal do cliente
   * @param state Estado do cliente
   */
  static private void disconnectClient(SocketChannel channel, ClientState state) {
    if (state != null) {
      // Sai da sala se estiver em uma
      if (state.state.equals("inside")) {
        leaveRoom(state);
      }
      
      // Remove nickname do mapa global
      if (state.nick != null) {
        clients.remove(state.nick);
      }
      
      // Remove das estruturas de mapeamento
      channelToClient.remove(channel);
    }
    
    // Limpa buffer de mensagens parciais
    partialMessages.remove(channel);
  }

  // =============================================
  // MÉTODOS AUXILIARES
  // =============================================
  
  /**
   * Verifica se nickname está disponível.
   * 
   * @param nick Nickname a verificar
   * @return true se disponível, false se já em uso
   */
  static private boolean isNickAvailable(String nick) {
    return !clients.containsKey(nick) && 
           nick != null && 
           !nick.isEmpty() && 
           !nick.contains(" "); // Nicknames não podem ter espaços
  }

  // =============================================
  // CLASSE INTERNA PARA ESTADO DO CLIENTE
  // =============================================
  
  /**
   * Representa o estado de um cliente conectado.
   * Controla nickname, estado atual e sala.
   */
  static private class ClientState {
    /** Canal de comunicação com o cliente */
    SocketChannel channel;
    
    /** Nickname do usuário (null se não definido) */
    String nick;
    
    /** 
     * Estado atual do cliente:
     * - "init": conectado, sem nickname
     * - "outside": com nickname, fora de sala  
     * - "inside": em sala de chat
     */
    String state = "init";
    
    /** Sala atual (null se não está em sala) */
    String room;
  }
}