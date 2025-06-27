package com.example.ftpservers.auth;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

import static com.example.ftpservers.Server.SimpleFTPServer.ROOT_DIR;

public class UserManager {

    private final File userFile = new File("users.txt");
    private Map<String, String> users = new HashMap<>();

    public UserManager() {
        loadUsers();
    }

    private synchronized void loadUsers() {
        users.clear();
        if (!userFile.exists()) {
            return;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(userFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    users.put(parts[0], parts[1]);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private synchronized void saveUsers() {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(userFile))) {
            for (Map.Entry<String, String> entry : users.entrySet()) {
                bw.write(entry.getKey() + ":" + entry.getValue());
                bw.newLine();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public synchronized boolean register(String username, String password) {
        if (users.containsKey(username)) {
            return false;
        }
        users.put(username, password);
        saveUsers();

        File userDir = new File(ROOT_DIR, username);
        if (!userDir.exists()) {
            userDir.mkdirs();
        }

        return true;
    }

    public boolean authenticate(String username, String password) {
        return password.equals(users.get(username));
    }
}
