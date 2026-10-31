// PPSS55 launcher probe. Everything reported here is queried from the device at run time:
// - Vulkan: libvulkan.so is opened with dlopen, an instance is created and every physical device is
//   asked for its properties (and driver properties on Vulkan 1.2+).
// - CPU: /proc/cpuinfo, cpufreq and the kernel's HWCAP bits that FEXCore relies on.
// - Core: dlopen() of the emulator library exactly as SDLActivity will load it; dlerror() is returned.
#include <jni.h>
#include <dlfcn.h>
#include <sys/auxv.h>
#include <unistd.h>

#include <cstdio>
#include <fstream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

#define VK_NO_PROTOTYPES 1
#include <vulkan/vulkan.h>

namespace {

std::string Version(uint32_t v) {
	std::ostringstream s;
	s << VK_API_VERSION_MAJOR(v) << '.' << VK_API_VERSION_MINOR(v) << '.' << VK_API_VERSION_PATCH(v);
	return s.str();
}

std::string VulkanInfo() {
	std::ostringstream out;
	void* lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
	if (lib == nullptr) {
		const char* e = dlerror();
		out << "error=libvulkan.so não carregou: " << (e ? e : "?") << '\n';
		return out.str();
	}
	auto getProc = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(lib, "vkGetInstanceProcAddr"));
	if (getProc == nullptr) {
		out << "error=vkGetInstanceProcAddr ausente\n";
		return out.str();
	}
	uint32_t loaderVersion = VK_API_VERSION_1_0;
	if (auto enumVer = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(getProc(nullptr, "vkEnumerateInstanceVersion"))) {
		enumVer(&loaderVersion);
	}
	out << "instance_version=" << Version(loaderVersion) << '\n';

	auto createInstance = reinterpret_cast<PFN_vkCreateInstance>(getProc(nullptr, "vkCreateInstance"));
	VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
	app.pApplicationName = "PPSS55 probe";
	app.apiVersion       = loaderVersion >= VK_API_VERSION_1_1 ? VK_API_VERSION_1_1 : VK_API_VERSION_1_0;
	VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
	ci.pApplicationInfo = &app;
	VkInstance instance = VK_NULL_HANDLE;
	VkResult   r        = createInstance ? createInstance(&ci, nullptr, &instance) : VK_ERROR_INITIALIZATION_FAILED;
	if (r != VK_SUCCESS) {
		out << "error=vkCreateInstance falhou (VkResult " << r << ")\n";
		return out.str();
	}
	auto enumDevices = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(getProc(instance, "vkEnumeratePhysicalDevices"));
	auto getProps    = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(getProc(instance, "vkGetPhysicalDeviceProperties"));
	auto getProps2   = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(getProc(instance, "vkGetPhysicalDeviceProperties2"));
	auto getMem      = reinterpret_cast<PFN_vkGetPhysicalDeviceMemoryProperties>(getProc(instance, "vkGetPhysicalDeviceMemoryProperties"));
	auto destroy     = reinterpret_cast<PFN_vkDestroyInstance>(getProc(instance, "vkDestroyInstance"));

	uint32_t count = 0;
	enumDevices(instance, &count, nullptr);
	std::vector<VkPhysicalDevice> devices(count);
	enumDevices(instance, &count, devices.data());
	out << "device_count=" << count << '\n';
	for (uint32_t i = 0; i < count; i++) {
		VkPhysicalDeviceProperties p{};
		getProps(devices[i], &p);
		out << "device" << i << ".name=" << p.deviceName << '\n';
		out << "device" << i << ".api=" << Version(p.apiVersion) << '\n';
		char hex[32];
		std::snprintf(hex, sizeof(hex), "0x%04x:0x%08x", p.vendorID, p.deviceID);
		out << "device" << i << ".ids=" << hex << '\n';
		out << "device" << i << ".driver_version_raw=" << p.driverVersion << '\n';
		if (getProps2 != nullptr && p.apiVersion >= VK_API_VERSION_1_2) {
			VkPhysicalDeviceDriverProperties drv{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES};
			VkPhysicalDeviceProperties2      p2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
			p2.pNext = &drv;
			getProps2(devices[i], &p2);
			out << "device" << i << ".driver=" << drv.driverName << " " << drv.driverInfo << '\n';
		}
		if (getMem != nullptr) {
			VkPhysicalDeviceMemoryProperties m{};
			getMem(devices[i], &m);
			uint64_t local = 0;
			for (uint32_t h = 0; h < m.memoryHeapCount; h++) {
				if (m.memoryHeaps[h].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) local += m.memoryHeaps[h].size;
			}
			out << "device" << i << ".device_local_mib=" << (local >> 20) << '\n';
		}
	}
	if (destroy) destroy(instance, nullptr);
	return out.str();
}

std::string CpuInfo() {
	std::ostringstream out;
	std::ifstream      f("/proc/cpuinfo");
	std::string        line;
	std::map<std::string, int> parts;
	std::string hardware;
	while (std::getline(f, line)) {
		auto colon = line.find(':');
		if (colon == std::string::npos) continue;
		std::string key = line.substr(0, colon);
		while (!key.empty() && (key.back() == ' ' || key.back() == '\t')) key.pop_back();
		std::string value = line.substr(colon + 1);
		if (!value.empty() && value[0] == ' ') value.erase(0, 1);
		if (key == "CPU part") parts[value]++;
		if (key == "Hardware") hardware = value;
	}
	long cores = sysconf(_SC_NPROCESSORS_CONF);
	out << "cores=" << cores << '\n';
	if (!hardware.empty()) out << "hardware=" << hardware << '\n';
	for (auto& [part, n] : parts) out << "part=" << part << " x" << n << '\n';
	for (long c = 0; c < cores; c++) {
		std::ifstream fr("/sys/devices/system/cpu/cpu" + std::to_string(c) + "/cpufreq/cpuinfo_max_freq");
		long khz = 0;
		if (fr >> khz) out << "cpu" << c << ".max_mhz=" << khz / 1000 << '\n';
	}
#if defined(__aarch64__)
	unsigned long hw  = getauxval(AT_HWCAP);
	unsigned long hw2 = getauxval(AT_HWCAP2);
	// Features FEXCore uses for x86 semantics (atomics, flag manipulation, RCpc loads).
	out << "atomics_lse=" << ((hw & (1UL << 8)) ? 1 : 0) << '\n';
	out << "flagm=" << ((hw & (1UL << 27)) ? 1 : 0) << '\n';
	out << "lrcpc=" << ((hw & (1UL << 15)) ? 1 : 0) << '\n';
	out << "lrcpc2=" << ((hw & (1UL << 20)) ? 1 : 0) << '\n';
	out << "sve=" << ((hw & (1UL << 22)) ? 1 : 0) << '\n';
	out << "sve2=" << ((hw2 & (1UL << 1)) ? 1 : 0) << '\n';
	out << "afp=" << ((hw2 & (1UL << 20)) ? 1 : 0) << '\n';
#endif
	long pages = sysconf(_SC_PHYS_PAGES);
	long psize = sysconf(_SC_PAGESIZE);
	out << "ram_mib=" << (static_cast<long long>(pages) * psize >> 20) << '\n';
	out << "page_size=" << psize << '\n';
	return out.str();
}

std::string TryLoad(const std::string& dir) {
	std::ostringstream out;
	// SDLActivity loads SDL3 first, then the emulator; do the same so DT_NEEDED resolves the same way.
	for (const char* name : {"libSDL3.so", "libmain.so"}) {
		std::string path = dir + "/" + name;
		void* h = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
		if (h == nullptr) {
			const char* e = dlerror();
			out << "error=" << name << ": " << (e ? e : "dlopen falhou") << '\n';
			return out.str();
		}
		out << "loaded=" << name << '\n';
		if (std::string(name) == "libmain.so") {
			out << "SDL_main=" << (dlsym(h, "SDL_main") ? "ok" : "ausente") << '\n';
		}
	}
	return out.str();
}

jstring J(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

} // namespace

extern "C" JNIEXPORT jstring JNICALL Java_com_ppss55_DeviceProbe_nativeVulkanInfo(JNIEnv* env, jclass) {
	return J(env, VulkanInfo());
}

extern "C" JNIEXPORT jstring JNICALL Java_com_ppss55_DeviceProbe_nativeCpuInfo(JNIEnv* env, jclass) {
	return J(env, CpuInfo());
}

extern "C" JNIEXPORT jstring JNICALL Java_com_ppss55_DeviceProbe_nativeTryLoadCore(JNIEnv* env, jclass, jstring dir) {
	const char* c = env->GetStringUTFChars(dir, nullptr);
	std::string d(c);
	env->ReleaseStringUTFChars(dir, c);
	return J(env, TryLoad(d));
}
