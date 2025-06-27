package com.example.ftpservers.Client;

import com.example.ftpservers.auth.UserManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SimpleFTPClientController {

    @FXML
    private TextField tfHost, tfPort, tfUser;

    @FXML
    private PasswordField tfPass;

    @FXML
    private Button btnConnect,btnRegister, btnList, btnDownload, btnUpload, btnDelete, btnUp, btnMkdir, btnRmdir, btnDisconnect, btnNewWindow;;

    @FXML
    private TableView<FTPItem> tableView;

    @FXML
    private TableColumn<FTPItem, String> colName;

    @FXML
    private TableColumn<FTPItem, String> colType;

    @FXML
    private TableColumn<FTPItem, Long> colSize;

    @FXML
    private TableColumn<FTPItem, String> colTime;

    @FXML
    private Label lblStatus;

    private Socket controlSocket;
    private BufferedReader reader;
    private BufferedWriter writer;

    private Socket dataSocket;

    private String currentDir = "/";  // 当前目录

    private UserManager userManager = new UserManager();

    @FXML
    private void initialize() {
        colName.setCellValueFactory(new PropertyValueFactory<>("name"));
        colType.setCellValueFactory(new PropertyValueFactory<>("type"));
        colSize.setCellValueFactory(new PropertyValueFactory<>("size"));
        colTime.setCellValueFactory(new PropertyValueFactory<>("lastModified"));

        btnList.setDisable(true);
        btnDownload.setDisable(true);
        btnUpload.setDisable(true);
        btnDelete.setDisable(true);
        btnUp.setDisable(true);
        btnMkdir.setDisable(true);
        btnRmdir.setDisable(true);
        btnDisconnect.setDisable(true);

        btnConnect.setOnAction(e -> connectFTP());
        btnRegister.setOnAction(e->register());
        btnList.setOnAction(e -> listFiles());
        btnDownload.setOnAction(e -> downloadFile());
        btnUpload.setOnAction(e -> uploadFile());
        btnDelete.setOnAction(e -> deleteFile());
        btnUp.setOnAction(e -> goUpDirectory());  // 返回上级目录
        btnMkdir.setOnAction(e -> makeDirectory());
        btnRmdir.setOnAction(e -> removeDirectory());
        btnDisconnect.setOnAction(e -> disconnectFTP());
        btnNewWindow.setOnAction(e->handleNewConnection());


        // 双击目录进入
        tableView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                FTPItem selectedItem = tableView.getSelectionModel().getSelectedItem();
                if (selectedItem != null && selectedItem.getType().equals("DIR")) {
                    changeDirectory(selectedItem.getName());
                }
            }
        });
    }

    private void connectFTP() {
        try {
            controlSocket = new Socket(tfHost.getText(), Integer.parseInt(tfPort.getText()));
            reader = new BufferedReader(new InputStreamReader(controlSocket.getInputStream()));
            writer = new BufferedWriter(new OutputStreamWriter(controlSocket.getOutputStream()));

            String response = readResponse();
            if (!response.startsWith("220")) {
                updateStatus("Server refused connection: " + response);
                return;
            }

            sendCommand("USER " + tfUser.getText());
            response = readResponse();
            if (!response.startsWith("331")) {
                updateStatus("USER command failed: " + response);
                return;
            }

            sendCommand("PASS " + tfPass.getText());
            response = readResponse();
            if (!response.startsWith("230")) {
                updateStatus("Login failed: " + response);
                return;
            }

            updateStatus("Connected and logged in");
            btnConnect.setDisable(true);
            btnRegister.setDisable(true);
            btnList.setDisable(false);
            btnDownload.setDisable(false);
            btnUpload.setDisable(false);
            btnDelete.setDisable(false);
            btnUp.setDisable(false);
            btnMkdir.setDisable(false);
            btnRmdir.setDisable(false);
            btnDisconnect.setDisable(false);
            btnNewWindow.setDisable(false);

            sendCommand("CWD /");
            readResponse();
            currentDir = "/";
            listFiles();

        } catch (Exception e) {
            updateStatus("Connection error: " + e.getMessage());
        }
    }

    private void register() {
        String username = tfUser.getText();
        String password = tfPass.getText();

        if (userManager.register(username, password)) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Регистрация прошла успешно");
            alert.setHeaderText(null);
            alert.setContentText("Пользователь " + username + " успешно зарегистрирован!");
            alert.showAndWait();

            tfUser.clear();
            tfPass.clear();
        } else {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Ошибка регистрации");
            alert.setHeaderText(null);
            alert.setContentText("Пользователь уже существует. Регистрация не удалась!");
            alert.showAndWait();
        }
    }

    @FXML
    private void handleNewConnection() {
        try {
            SimpleFTPClientFX clientApp = new SimpleFTPClientFX();
            clientApp.openNewWindow(); // 打开新窗口
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private void listFiles() {
        new Thread(() -> {
            try {
                enterPassiveMode();
                sendCommand("LIST");
                String resp = readResponse();
                if (!resp.startsWith("150")) {
                    updateStatus("LIST failed: " + resp);
                    return;
                }
                BufferedReader dataReader = new BufferedReader(new InputStreamReader(dataSocket.getInputStream()));
                var items = new ArrayList<FTPItem>();
                String line;
                while ((line = dataReader.readLine()) != null) {
                    FTPItem item = parseFTPItem(line);
                    items.add(item);
                }
                dataSocket.close();
                readResponse();

                // UI更新必须在FX线程
                Platform.runLater(() -> {
                    tableView.getItems().setAll(items);
                    updateStatus("Current directory: " + currentDir);
                });
            } catch (Exception e) {
                Platform.runLater(() -> updateStatus("List error: " + e.getMessage()));
            }
        }).start();
    }


    private FTPItem parseFTPItem(String line) {
        // 按制表符（\t）拆分
        String[] parts = line.split("\t");
        if (parts.length != 4) {
            // 解析失败，返回基本对象
            return new FTPItem(line, "UNKNOWN", 0, "");
        }

        String name = parts[0];
        String type = parts[1];
        long size;
        try {
            size = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            size = 0;
        }
        // 转换成KB，向下取整
        long sizeInKB = size / 1024;
        String lastModified = parts[3];

        return new FTPItem(name, type, sizeInKB, lastModified);
    }

    // 进入指定目录
    private void changeDirectory(String dirName) {
        try {
            sendCommand("CWD " + dirName);
            String resp = readResponse();
            if (resp.startsWith("250")) {
                if (!currentDir.endsWith("/")) currentDir += "/";
                currentDir += dirName + "/";
//                updateCurrentDirectoryFromServer();
                updateStatus("Changed directory to " + currentDir);
                listFiles();
            } else {
                updateStatus("Failed to change directory: " + resp);
            }
        } catch (IOException e) {
            updateStatus("Error changing directory: " + e.getMessage());
        }
    }

    private void updateCurrentDirectoryFromServer() throws IOException {
        sendCommand("PWD");
        String resp = readResponse();

        if (resp == null) {
            System.err.println("The PWD response returned by the server is null!");
            return;
        }

        // 服务器返回类似： 257 "/path/to/current" is current directory
        Pattern pattern = Pattern.compile("\"(.*?)\"");
        Matcher matcher = pattern.matcher(resp);

        if (matcher.find()) {
            currentDir = matcher.group(1);
        } else {
            System.err.println("Failed to match to the current directory path, server response:" + resp);
        }
    }

    // 返回上级目录
    private void goUpDirectory() {
        try {
            sendCommand("CDUP");
            String resp = readResponse();
            if (resp.startsWith("200") || resp.startsWith("250")) {
                // 更新currentDir路径，去掉最后一级目录
                if (currentDir.length() > 1) {
                    currentDir = currentDir.substring(0, currentDir.lastIndexOf('/', currentDir.length() - 2) + 1);
                }
                updateStatus("Returned to directory " + currentDir);
                listFiles();
            } else {
                updateStatus("Failed to go up directory: " + resp);
            }
        } catch (IOException e) {
            updateStatus("Error going up directory: " + e.getMessage());
        }
    }


    private void downloadFile() {
        FTPItem selectedItem = tableView.getSelectionModel().getSelectedItem();
        if (selectedItem == null || selectedItem.getType().equals("DIR")) {
            updateStatus("Please select a file to download");
            return;
        }
        String filename = selectedItem.getName();

        FileChooser fileChooser = new FileChooser();
        fileChooser.setInitialFileName(filename);
        File saveFile = fileChooser.showSaveDialog(getStage());
        if (saveFile == null) return;

        try {
            enterPassiveMode();

            sendCommand("RETR " + filename);
            String resp = readResponse();
            if (!resp.startsWith("150")) {
                updateStatus("RETR failed: " + resp);
                return;
            }

            InputStream dataIn = dataSocket.getInputStream();
            InputStream base64In = Base64.getDecoder().wrap(dataIn);
            FileOutputStream fileOut = new FileOutputStream(saveFile);

            byte[] buffer = new byte[4096];
            int count;
            while ((count = base64In.read(buffer)) > 0) {
                fileOut.write(buffer, 0, count);
            }
            fileOut.close();
            dataSocket.close();


            updateStatus(readResponse() + " Download complete: " + filename);

        } catch (Exception e) {
            updateStatus("Download error: " + e.getMessage());
        }
    }

    private void uploadFile() {
        FileChooser fileChooser = new FileChooser();
        File file = fileChooser.showOpenDialog(getStage());
        if (file == null) return;

        try {
            enterPassiveMode();

            sendCommand("STOR " + file.getName());
            String resp = readResponse();
            if (!resp.startsWith("150")) {
                updateStatus("STOR failed: " + resp);
                return;
            }

            OutputStream dataOut = dataSocket.getOutputStream();
            OutputStream base64Out = Base64.getEncoder().wrap(dataOut);
            FileInputStream fileIn = new FileInputStream(file);

            byte[] buffer = new byte[4096];
            int count;
            while ((count = fileIn.read(buffer)) > 0) {
                base64Out.write(buffer, 0, count);
            }
            base64Out.flush();
            fileIn.close();
            dataSocket.close();


            updateStatus(readResponse() + " Upload complete: " + file.getName());
            listFiles();

        } catch (Exception e) {
            updateStatus("Upload error: " + e.getMessage());
        }
    }

    private void deleteFile() {
        FTPItem selectedItem = tableView.getSelectionModel().getSelectedItem();
        if (selectedItem == null || selectedItem.getType().equals("DIR")){
            updateStatus("Please select a file (not a directory) to delete");
            return;
        }

        String filename = selectedItem.getName();

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Confirm Delete");
        alert.setHeaderText("Are you sure you want to delete this file?");
        alert.setContentText(filename);

        alert.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK) {
                try {
                    sendCommand("DELE " + filename);
                    String resp = readResponse();
                    if (resp.startsWith("250")) {
                        updateStatus("Deleted: " + filename);
                        listFiles();
                    } else {
                        updateStatus("Delete failed: " + resp);
                    }
                } catch (IOException e) {
                    updateStatus("Delete error: " + e.getMessage());
                }
            }
        });
    }


    private void enterPassiveMode() throws IOException {
        sendCommand("PASV");
        String resp = readResponse();

        if (!resp.startsWith("227")) {
            throw new IOException("Failed to enter passive mode: " + resp);
        }

        Pattern pattern = Pattern.compile("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)");
        Matcher matcher = pattern.matcher(resp);

        if (!matcher.find()) {
            throw new IOException("Invalid PASV response format: " + resp);
        }

        String ip = matcher.group(1) + "." +
                matcher.group(2) + "." +
                matcher.group(3) + "." +
                matcher.group(4);

        int p1 = Integer.parseInt(matcher.group(5));
        int p2 = Integer.parseInt(matcher.group(6));
        int port = (p1 << 8) + p2;

        dataSocket = new Socket(ip, port);
    }

    private void makeDirectory() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("New Folder");
        dialog.setHeaderText("Enter name for new folder:");
        dialog.setContentText("Folder name:");

        dialog.showAndWait().ifPresent(folderName -> {
            try {
                sendCommand("MKD " + folderName);
                String response = readResponse();
                if (response.startsWith("257")) {
                    updateStatus("Folder created: " + folderName);
                    listFiles(); // 刷新列表
                } else {
                    updateStatus("Create folder failed: " + response);
                }
            } catch (IOException e) {
                updateStatus("Error creating folder: " + e.getMessage());
            }
        });
    }

    private void removeDirectory() {
        FTPItem selectedItem = tableView.getSelectionModel().getSelectedItem();
        if (selectedItem == null || !selectedItem.getType().equals("DIR")) {
            updateStatus("Please select a directory to delete");
            return;
        }

        String folderName = selectedItem.getName();

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Folder");
        confirm.setHeaderText("Are you sure you want to delete the folder: " + folderName + "?");

        confirm.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK) {
                try {
                    sendCommand("RMD " + folderName);
                    String response = readResponse();
                    if (response.startsWith("250")) {
                        updateStatus("Folder deleted: " + folderName);
                        listFiles();
                    } else {
                        updateStatus("Delete folder failed: " + response);
                    }
                } catch (IOException e) {
                    updateStatus("Error deleting folder: " + e.getMessage());
                }
            }
        });
    }

    @FXML
    private void disconnectFTP() {
        try {
            if (controlSocket != null && !controlSocket.isClosed()) {
                sendCommand("QUIT");
                readResponse();
                controlSocket.close();
            }
        } catch (IOException e) {
            updateStatus("Error during disconnect: " + e.getMessage());
        } finally {
            controlSocket = null;
            reader = null;
            writer = null;
            dataSocket = null;

            btnConnect.setDisable(false);
            btnRegister.setDisable(false);
            btnList.setDisable(true);
            btnDownload.setDisable(true);
            btnUpload.setDisable(true);
            btnDelete.setDisable(true);
            btnUp.setDisable(true);
            btnMkdir.setDisable(true);
            btnRmdir.setDisable(true);
            btnDisconnect.setDisable(true);
            tableView.getItems().clear();
            updateStatus("Disconnected");
        }
    }


    private void sendCommand(String cmd) throws IOException {
        writer.write(cmd + "\r\n");
        writer.flush();
        System.out.println("Sent: " + cmd);
    }

    private String readResponse() throws IOException {
        String resp = reader.readLine();
        System.out.println("Received: " + resp);
        return resp;
    }

    private void updateStatus(String msg) {
        lblStatus.setText(msg);
        System.out.println(msg);
    }

    private Stage getStage() {
        return (Stage) lblStatus.getScene().getWindow();
    }
}