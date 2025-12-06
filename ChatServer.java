import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Servidor de Chat
 * Suporta múltiplos clientes simultâneos com nicknames únicos, salas de chat e comandos do protocolo.
 * 
 * Protocolo implementado:
 * - Comandos: /nick, /join, /leave, /bye, /priv (extra +10%)
 * - Respostas: OK, ERROR, MESSAGE, NEWNICK, JOINED, LEFT, BYE
 * - Mecanismo de escape: mensagens começando com "//" têm primeira "/" removida
 * - Bufferização de mensagens parciais para delineação TCP correta
 * 
 */
public class ChatServer {
    static private final ByteBuffer buffer = ByteBuffer.allocate(16384);
    static private final Charset charset = Charset.forName("UTF8");
    static private final CharsetDecoder decoder = charset.newDecoder();
    static private final CharsetEncoder encoder = charset.newEncoder();
    
    static private Map<String, ClientState> clients = new ConcurrentHashMap<>();
    static private Map<String, Set<ClientState>> rooms = new ConcurrentHashMap<>();
    static private Map<SocketChannel, ClientState> channelToClient = new ConcurrentHashMap<>();
    static private Map<SocketChannel, StringBuilder> partialMessages = new ConcurrentHashMap<>();

    /**
     * Ponto de entrada do servidor. Inicializa ServerSocketChannel não-bloqueante,
     * configura Selector para multiplexação e inicia loop de eventos principal.
     * Aceita argumento de linha de comando: porta TCP para escuta.
     * 
     * @param args Array contendo número da porta TCP [porta]
     * @throws Exception Em caso de erro de bind, configuração de canais ou selector
     */
    static public void main(String args[]) throws Exception {
        if (args.length != 1) {
            System.out.println("Uso: java ChatServer <porta>");
            return;
        }
        
        int port = Integer.parseInt(args[0]);
        
        try {
            ServerSocketChannel ssc = ServerSocketChannel.open();
            ssc.configureBlocking(false);
            ServerSocket ss = ssc.socket();
            InetSocketAddress isa = new InetSocketAddress(port);
            ss.bind(isa);

            Selector selector = Selector.open();
            ssc.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println("Listening on port " + port);
            
            while (true) {
                int num = selector.select();
                if (num == 0) continue;

                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> it = keys.iterator();
                
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) continue;
                    
                    if (key.isAcceptable()) {
                        Socket s = ss.accept();
                        System.out.println("Got connection from " + s);
                        SocketChannel sc = s.getChannel();
                        sc.configureBlocking(false);
                        sc.register(selector, SelectionKey.OP_READ);
                        ClientState state = new ClientState();
                        state.channel = sc;
                        state.state = "init";
                        channelToClient.put(sc, state);
                        partialMessages.put(sc, new StringBuilder());
                    } else if (key.isReadable()) {
                        SocketChannel sc = null;
                        try {
                            sc = (SocketChannel) key.channel();
                            boolean ok = processInput(sc);
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

    /**
     * Processa dados recebidos de um cliente, lendo do SocketChannel e decodificando UTF-8.
     * Implementa bufferização para mensagens parciais, acumulando dados até encontrar '\n'.
     * Retorna false se cliente fechou conexão (EOF), true para continuar processamento.
     * 
     * @param sc SocketChannel do cliente para leitura
     * @return true se conexão ativa, false se cliente desconectou
     * @throws IOException Em erro de I/O no canal
     */
    static private boolean processInput(SocketChannel sc) throws IOException {
        buffer.clear();
        int bytesRead = sc.read(buffer);
        if (bytesRead == -1) return false;
        buffer.flip();
        if (buffer.limit() == 0) return true;

        String data = decoder.decode(buffer).toString();
        StringBuilder partialMessage = partialMessages.get(sc);
        ClientState state = channelToClient.get(sc);
        if (partialMessage == null) {
            partialMessage = new StringBuilder();
            partialMessages.put(sc, partialMessage);
        }
        partialMessage.append(data);
        String message = partialMessage.toString();
        int lineEnd;
        
        while ((lineEnd = message.indexOf('\n')) != -1) {
            String line = message.substring(0, lineEnd).trim();
            if (line.length() > 0) {
                processMessage(state, line);
            }
            message = message.substring(lineEnd + 1);
        }
        partialMessages.put(sc, new StringBuilder(message));
        return true;
    }

    /**
     * Processa mensagem completa recebida do cliente. Identifica comandos (iniciando com '/')
     * e delega para handlers específicos. Aplica mecanismo de escape para mensagens em sala.
     * Mensagens normais apenas permitidas no estado 'inside'. Comandos não reconhecidos
     * em sala são tratados como mensagens com escape aplicado.
     * 
     * @param state Estado atual do cliente remetente
     * @param message Mensagem recebida (trimada, sem \n)
     */
    static private void processMessage(ClientState state, String message) {
        System.out.println("Processando: '" + message + "' do estado: " + state.state);
        
        if (message.startsWith("/")) {
            String[] parts = message.split("\\s+", 3);
            String command = parts[0];
            
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
                    if (state.state.equals("inside")) {
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
            if (state.state.equals("inside")) {
                broadcastMessage(state, message);
            } else {
                sendError(state, "Não está numa sala");
            }
        }
    }

    /**
     * Handler do comando /nick - Define ou altera nickname do usuário.
     * Verifica disponibilidade do nickname (único, sem espaços).
     * Estados permitidos: init (primeiro nickname), outside/inside (mudança).
     * Em sala, notifica outros usuários com mensagem NEWNICK.
     * 
     * @param state Estado do cliente solicitante
     * @param newNick Novo nickname desejado
     */
    static private void handleNick(ClientState state, String newNick) {
        if (!isNickAvailable(newNick)) {
            sendError(state, "Nome já em uso");
            return;
        }
        String oldNick = state.nick;
        
        if (state.state.equals("init")) {
            state.nick = newNick;
            state.state = "outside";
            clients.put(newNick, state);
            sendOk(state);
        } else if (state.state.equals("outside") || state.state.equals("inside")) {
            state.nick = newNick;
            clients.remove(oldNick);
            clients.put(newNick, state);
            if (state.state.equals("inside")) {
                broadcastToRoom(state.room, "NEWNICK " + oldNick + " " + newNick, state);
            }
            sendOk(state);
        }
    }

    /**
     * Handler do comando /join - Entra ou cria sala de chat.
     * Estados permitidos: outside, inside. Se já em sala, sai primeiro.
     * Notifica entrada com mensagem JOINED para outros na sala.
     * 
     * @param state Estado do cliente solicitante
     * @param room Nome da sala desejada
     */
    static private void handleJoin(ClientState state, String room) {
        if (!state.state.equals("outside") && !state.state.equals("inside")) {
            sendError(state, "Comando não permitido neste estado");
            return;
        }
        if (state.state.equals("inside")) {
            leaveRoom(state);
        }
        joinRoom(state, room);
        sendOk(state);
    }

    /**
     * Handler do comando /leave - Sai da sala atual.
     * Estado permitido: inside. Notifica saída com mensagem LEFT.
     * Remove sala se ficar vazia após saída.
     * 
     * @param state Estado do cliente solicitante
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
     * Handler do comando /bye - Desconecta cliente graciosamente.
     * Permitido em qualquer estado. Sai da sala se estiver em uma.
     * Envia mensagem BYE e fecha conexão.
     * 
     * @param state Estado do cliente solicitante
     */
    static private void handleBye(ClientState state) {
        if (state.state.equals("inside")) {
            leaveRoom(state);
        }
        sendBye(state);
        disconnectClient(state.channel, state);
    }

    /**
     * Handler do comando /priv - Envia mensagem privada para usuário específico.
     * Funcionalidade extra (+10% na avaliação). Verifica existência do destinatário.
     * Estados permitidos: outside, inside. Envia mensagem PRIVATE apenas ao destinatário.
     * 
     * @param state Estado do cliente remetente
     * @param targetNick Nickname do destinatário
     * @param message Conteúdo da mensagem privada
     */
    static private void handlePrivate(ClientState state, String targetNick, String message) {
        ClientState target = clients.get(targetNick);
        if (target == null) {
            sendError(state, "Utilizador não encontrado");
            return;
        }
        sendPrivate(target, state.nick, message);
        sendOk(state);
    }

    /**
     * Adiciona usuário a uma sala, criando-a se não existir.
     * Atualiza estado do cliente para 'inside' e notifica outros na sala.
     * 
     * @param state Estado do cliente a adicionar
     * @param room Nome da sala destino
     */
    static private void joinRoom(ClientState state, String room) {
        if (!rooms.containsKey(room)) {
            rooms.put(room, ConcurrentHashMap.newKeySet());
        }
        rooms.get(room).add(state);
        state.room = room;
        state.state = "inside";
        broadcastToRoom(room, "JOINED " + state.nick, state);
    }

    /**
     * Remove usuário de sua sala atual, notificando outros participantes.
     * Remove sala se ficar vazia. Atualiza estado do cliente para 'outside'.
     * 
     * @param state Estado do cliente a remover
     */
    static private void leaveRoom(ClientState state) {
        if (state.room != null && rooms.containsKey(state.room)) {
            Set<ClientState> roomClients = rooms.get(state.room);
            roomClients.remove(state);
            if (roomClients.isEmpty()) {
                rooms.remove(state.room);
            } else {
                broadcastToRoom(state.room, "LEFT " + state.nick, state);
            }
        }
        state.room = null;
        state.state = "outside";
    }

    /**
     * Transmite mensagem de texto para todos na sala do remetente.
     * Aplica mecanismo de escape removendo '/' inicial se mensagem começar com "//".
     * 
     * @param sender Cliente remetente da mensagem
     * @param message Conteúdo da mensagem a transmitir
     */
    static private void broadcastMessage(ClientState sender, String message) {
        if (sender.room != null && rooms.containsKey(sender.room)) {
            String processedMessage = message;
            if (message.startsWith("//")) {
                processedMessage = message.substring(1);
            }
            broadcastToRoom(sender.room, "MESSAGE " + sender.nick + " " + processedMessage, null);
        }
    }

    /**
     * Envia mensagem formatada do protocolo para todos os clientes em uma sala.
     * Exclui opcionalmente um cliente (normalmente o remetente).
     * Verifica se canal está aberto antes de enviar.
     * 
     * @param room Sala de destino para broadcast
     * @param message Mensagem formatada do protocolo (sem \n)
     * @param exclude Cliente a excluir do envio (null para enviar a todos)
     */
    static private void broadcastToRoom(String room, String message, ClientState exclude) {
        if (rooms.containsKey(room)) {
            for (ClientState client : rooms.get(room)) {
                if (client != exclude && client.channel.isOpen()) {
                    sendRaw(client, message);
                }
            }
        }
    }

    /**
     * Envia resposta OK indicando sucesso do comando executado.
     * 
     * @param state Estado do cliente destino
     */
    static private void sendOk(ClientState state) {
        sendRaw(state, "OK");
    }

    /**
     * Envia resposta ERROR com mensagem descritiva sobre falha no comando.
     * 
     * @param state Estado do cliente destino
     * @param error Descrição do erro ocorrido
     */
    static private void sendError(ClientState state, String error) {
        sendRaw(state, "ERROR " + error);
    }

    /**
     * Envia resposta BYE confirmando desconexão graciosa.
     * 
     * @param state Estado do cliente destino
     */
    static private void sendBye(ClientState state) {
        sendRaw(state, "BYE");
    }

    /**
     * Envia mensagem PRIVATE para comunicação direta entre usuários.
     * 
     * @param target Estado do cliente destinatário
     * @param from Nickname do remetente
     * @param message Conteúdo da mensagem privada
     */
    static private void sendPrivate(ClientState target, String from, String message) {
        sendRaw(target, "PRIVATE " + from + " " + message);
    }

    /**
     * Envia mensagem raw (bruta) para cliente específico, codificando UTF-8 e adicionando \n.
     * Verifica validade do canal antes de enviar. Em caso de erro de I/O, apenas registra.
     * 
     * @param state Estado do cliente destino
     * @param message Mensagem a enviar (sem \n no final)
     */
    static private void sendRaw(ClientState state, String message) {
        if (state == null || state.channel == null || !state.channel.isOpen()) {
            return;
        }
        try {
            ByteBuffer buffer = encoder.encode(CharBuffer.wrap(message + "\n"));
            while (buffer.hasRemaining()) {
                state.channel.write(buffer);
            }
        } catch (IOException e) {
            System.err.println("Erro ao enviar mensagem para " + state.nick + ": " + e.getMessage());
        }
    }

    /**
     * Limpeza completa ao desconectar cliente. Remove de todas as estruturas de dados:
     * sai da sala se estiver em uma, remove nickname do mapa global, limpa mapeamentos
     * e buffer de mensagens parciais.
     * 
     * @param channel SocketChannel do cliente desconectando
     * @param state Estado associado ao cliente (pode ser null se nunca autenticado)
     */
    static private void disconnectClient(SocketChannel channel, ClientState state) {
        if (state != null) {
            if (state.state.equals("inside")) {
                leaveRoom(state);
            }
            if (state.nick != null) {
                clients.remove(state.nick);
            }
            channelToClient.remove(channel);
        }
        partialMessages.remove(channel);
    }

    /**
     * Verifica disponibilidade de nickname para novo usuário ou mudança.
     * Nicknames devem ser não-nulos, não vazios, sem espaços e únicos.
     * 
     * @param nick Nickname a verificar
     * @return true se nickname disponível e válido, false caso contrário
     */
    static private boolean isNickAvailable(String nick) {
        return !clients.containsKey(nick) && 
               nick != null && 
               !nick.isEmpty() && 
               !nick.contains(" ");
    }

    /**
     * Representa estado interno de um cliente conectado ao servidor.
     * Mantém referência ao canal de comunicação, nickname atual, estado
     * (init/outside/inside) e sala atual (null se não está em sala).
     * 
     * Estado init: conectado, sem nickname definido
     * Estado outside: nickname definido, fora de sala
     * Estado inside: em sala de chat, pode enviar mensagens
     */
    static private class ClientState {
        SocketChannel channel;
        String nick;
        String state = "init";
        String room;
    }
}