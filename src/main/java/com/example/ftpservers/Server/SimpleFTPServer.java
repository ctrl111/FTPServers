    package com.example.ftpservers.Server;

    import com.example.ftpservers.auth.UserManager;
    import com.example.ftpservers.fileoperation.ThreadSafeFileManager;

    import java.io.*;
    import java.net.*;
    import java.text.SimpleDateFormat;
    import java.util.Base64;
    import java.util.Date;

    public class SimpleFTPServer {

        private static final int CONTROL_PORT = 2121; // FTP默认21端口，调试时用2121防冲突
        public static final String ROOT_DIR = "ftp_root";

        public static void main(String[] args) throws IOException {
            // 确保根目录存在
            File root = new File(ROOT_DIR);
            if (!root.exists()) root.mkdirs();

            ServerSocket serverSocket = new ServerSocket(CONTROL_PORT);
            System.out.println("FTP Server started on port " + CONTROL_PORT);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(new ClientHandler(clientSocket, ROOT_DIR)).start();
            }
        }

        static class ClientHandler implements Runnable {
            private Socket controlSocket;
            private BufferedReader reader;
            private BufferedWriter writer;
            private String rootDir;
            private File currentDir;
            private UserManager userManager = new UserManager();
            private String currentUser = null;

            private boolean loggedIn = false;
            private ServerSocket dataServerSocket;
            private Socket dataSocket;

            ClientHandler(Socket socket, String rootDir) throws IOException {
                this.controlSocket = socket;
                this.rootDir = rootDir;
                this.currentDir = new File(rootDir);
                reader = new BufferedReader(new InputStreamReader(controlSocket.getInputStream()));
                writer = new BufferedWriter(new OutputStreamWriter(controlSocket.getOutputStream()));
            }


            @Override
            public void run() {
                try {
                    sendResponse("220 Welcome to Simple FTP Server");

                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("Received: " + line);
                        String[] parts = line.split(" ", 2);
                        String cmd = parts[0].toUpperCase();
                        String arg = parts.length > 1 ? parts[1] : null;

                        switch (cmd) {
                            case "USER":
                                handleUSER(arg);
                                break;
                            case "PASS":
                                handlePASS(arg);
                                break;
                            case "PASV":
                                handlePASV();
                                break;
                            case "LIST":
                                handleLIST();
                                break;
                            case "RETR":
                                handleRETR(arg);
                                break;
                            case "STOR":
                                handleSTOR(arg);
                                break;
                            case "QUIT":
                                handleQUIT();
                                return;
                            case "DELE":
                                handleDELE(arg);
                                break;
                            case "PWD":
                                handlePWD();
                                break;
                            case "CWD":
                                handleCWD(arg);
                                break;
                            case "CDUP":
                                handleCDUP();
                                break;
                            case "MKD":
                                handleMKD(arg);
                                break;
                            case "RMD":
                                handleRMD(arg);
                                break;

                            default:
                                sendResponse("502 Command not implemented");
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    closeConnections();
                }
            }

            private void handleUSER(String user) throws IOException {
                this.currentUser = user;
                sendResponse("331 Username OK, need password");
            }

            private void handlePASS(String pass) throws IOException {
                if (currentUser != null && userManager.authenticate(currentUser, pass)) {
                    loggedIn = true;

                    // 设置用户当前目录为 ftp_root/用户名
                    currentDir = new File(ROOT_DIR, currentUser);
                    if (!currentDir.exists()) {
                        currentDir.mkdirs(); // 防止目录被手动删除
                    }
                    sendResponse("230 User logged in");
                } else {
                    sendResponse("530 Login incorrect");
                }
            }

            private void handlePASV() throws IOException {
                if (dataServerSocket != null && !dataServerSocket.isClosed()) {
                    dataServerSocket.close();
                }
                dataServerSocket = new ServerSocket(0);
                InetAddress addr = controlSocket.getLocalAddress();
                int port = dataServerSocket.getLocalPort();

                // FTP PASV格式 (h1,h2,h3,h4,p1,p2)
                byte[] ipBytes = addr.getAddress();
                int p1 = port / 256;
                int p2 = port % 256;
                String response = String.format("227 Entering Passive Mode (%d,%d,%d,%d,%d,%d)",
                        ipBytes[0] & 0xff, ipBytes[1] & 0xff, ipBytes[2] & 0xff, ipBytes[3] & 0xff, p1, p2);
                sendResponse(response);
            }

            private void handleLIST() throws IOException {
                if (!loggedIn) {
                    sendResponse("530 Not logged in");
                    return;
                }
                sendResponse("150 Opening data connection for directory list");

                openDataConnection();

                BufferedWriter dataWriter = new BufferedWriter(new OutputStreamWriter(dataSocket.getOutputStream()));
                File[] files = currentDir.listFiles();
                if (files != null) {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    for (File file : files) {
                        String name = file.getName();
                        String type;
                        if (file.isDirectory()) {
                            type = "DIR";
                        } else {
                            int lastDot = name.lastIndexOf('.');
                            if (lastDot > 0 && lastDot < name.length() - 1) {
                                type = name.substring(lastDot + 1).toUpperCase();
                            } else {
                                type = "FILE";
                            }
                        }
                        long size = file.isDirectory() ? 0L : file.length();
                        String lastModified = sdf.format(new Date(file.lastModified()));

                        dataWriter.write(String.format("%s\t%s\t%d\t%s\r\n", name, type, size, lastModified));
                    }

                }
                dataWriter.flush();
                closeDataConnection();

                sendResponse("226 Transfer complete");
            }

            private void handleRETR(String filename) throws IOException {
                if (!loggedIn) {
                    sendResponse("530 Not logged in");
                    return;
                }

                File file = new File(currentDir, filename);
                if (!file.exists() || file.isDirectory()) {
                    sendResponse("550 File not found");
                    return;
                }

                sendResponse("150 Opening data connection for file transfer");
                openDataConnection();

                try (Socket socket = dataSocket) {
                    OutputStream dataOut = socket.getOutputStream();
                    ThreadSafeFileManager.readFileBase64(file, dataOut);
                    sendResponse("226 Transfer complete");
                } catch (IOException e) {
                    sendResponse("451 Requested action aborted. Local error in processing.");
                } finally {
                    closeDataConnection();
                }
            }

            private void handleSTOR(String filename) throws IOException {
                if (!loggedIn) {
                    sendResponse("530 Not logged in");
                    return;
                }

                File file = new File(currentDir, filename);
                sendResponse("150 Opening data connection for file upload");
                openDataConnection();

                try (Socket socket = dataSocket) {
                    InputStream dataIn = socket.getInputStream();
                    ThreadSafeFileManager.writeFileBase64(file, dataIn);
                    sendResponse("226 Transfer complete");
                } catch (IOException e) {
                    sendResponse("451 Requested action aborted. Local error in processing.");
                } finally {
                    closeDataConnection();
                }
            }


            private void handleDELE(String filename) throws IOException {
                if (!loggedIn) {
                    sendResponse("530 Not logged in");
                    return;
                }

                if (filename == null || filename.isEmpty()) {
                    sendResponse("501 Syntax error in parameters or arguments");
                    return;
                }

                File file = new File(currentDir, filename);
                if (!file.exists() || file.isDirectory()) {
                    sendResponse("550 File not found or is a directory");
                    return;
                }

                if (file.delete()) {
                    sendResponse("250 File deleted successfully");
                } else {
                    sendResponse("450 Requested file action not taken");
                }
            }



            private void handleQUIT() throws IOException {
                sendResponse("221 Goodbye");
                closeConnections();
            }

            private void handlePWD() throws IOException {
                if (!loggedIn) {
                    sendResponse("530 Not logged in");
                    return;
                }
                String relativePath = currentDir.getAbsolutePath().replaceFirst(new File(rootDir).getAbsolutePath(), "");
                if (relativePath.isEmpty()) relativePath = "/";
                sendResponse("257 \"" + relativePath + "\" is current directory");
            }

            private void handleCWD(String dir) throws IOException {
                if (!loggedIn) {
                    sendResponse("530 Not logged in");
                    return;
                }
                if (dir == null || dir.isEmpty()) {
                    sendResponse("501 Missing directory name");
                    return;
                }

                File newDir = new File(currentDir, dir).getCanonicalFile();
                if (newDir.exists() && newDir.isDirectory()
                        && newDir.getAbsolutePath().startsWith(new File(rootDir).getAbsolutePath())) {
                    currentDir = newDir;
                    sendResponse("250 Directory changed to " + dir);
                } else {
                    sendResponse("550 Directory not found or access denied");
                }
            }

            private void handleCDUP() throws IOException {
                if (!loggedIn) {
                    sendResponse("530 Not logged in");
                    return;
                }

                File userRoot = new File(rootDir,currentUser).getCanonicalFile(); // rootDir 应该是 /ftp_root/user
                File parent = currentDir.getParentFile();

                if (parent == null) {
                    sendResponse("550 Cannot go to parent directory");
                    return;
                }

                File canonicalParent = parent.getCanonicalFile();

                // 如果已经是用户根目录了，就不能再往上
                if (currentDir.getCanonicalFile().equals(userRoot)) {
                    System.out.println(currentDir);
                    System.out.println(userRoot);
                    sendResponse("550 Already at the root directory");
                    return;
                }

                // 防止用户越界访问父级目录
                if (!canonicalParent.getPath().startsWith(userRoot.getPath())) {
                    sendResponse("550 Access denied to parent directory");
                    return;
                }

                currentDir = canonicalParent;
                sendResponse("200 Directory changed to parent");
            }

            private void handleMKD(String dirName) throws IOException {
                if (!loggedIn) {
                    sendResponse("530 Not logged in");
                    return;
                }

                if (dirName == null || dirName.isEmpty()) {
                    sendResponse("501 Directory name required");
                    return;
                }

                File newDir = new File(currentDir, dirName);
                if (newDir.mkdir()) {
                    sendResponse("257 \"" + dirName + "\" created");
                } else {
                    sendResponse("550 Failed to create directory");
                }
            }

            private void handleRMD(String dirName) throws IOException {
                if (!loggedIn) {
                    sendResponse("530 Not logged in");
                    return;
                }

                if (dirName == null || dirName.isEmpty()) {
                    sendResponse("501 Directory name required");
                    return;
                }

                File targetDir = new File(currentDir, dirName);
                if (!targetDir.exists()) {
                    sendResponse("550 Directory does not exist");
                } else if (!targetDir.isDirectory()) {
                    sendResponse("550 Not a directory");
                } else if (!targetDir.delete()) {
                    sendResponse("550 Directory not empty or no permission");
                } else {
                    sendResponse("250 Directory deleted");
                }

            }


            private void sendResponse(String msg) throws IOException {
                writer.write(msg + "\r\n");
                writer.flush();
                System.out.println("Sent: " + msg);
            }

            private void openDataConnection() throws IOException {
                if (dataServerSocket == null) {
                    sendResponse("425 Use PASV first");
                    throw new IOException("No data connection available");
                }
                dataSocket = dataServerSocket.accept();
                dataServerSocket.close();
                dataServerSocket = null;
            }

            private void closeDataConnection() throws IOException {
                if (dataSocket != null) {
                    dataSocket.close();
                    dataSocket = null;
                }
            }

            private void closeConnections() {
                try { if (reader != null) reader.close(); } catch (IOException ignored) {}
                try { if (writer != null) writer.close(); } catch (IOException ignored) {}
                try { if (controlSocket != null) controlSocket.close(); } catch (IOException ignored) {}
                try { if (dataSocket != null) dataSocket.close(); } catch (IOException ignored) {}
                try { if (dataServerSocket != null) dataServerSocket.close(); } catch (IOException ignored) {}
            }
        }
    }

